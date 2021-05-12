package org.renci.relationgraph

import java.io.{File, FileOutputStream, OutputStream}
import java.lang.{Runtime => JRuntime}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import caseapp._
import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.apache.jena.riot.RDFFormat
import org.apache.jena.riot.system.{StreamRDF, StreamRDFWriter}
import org.apache.jena.vocabulary.{OWL2, RDF, RDFS}
import org.geneontology.whelk.BuiltIn.{Bottom, Top}
import org.geneontology.whelk._
import org.renci.relationgraph.Config.{OWLMode, RDFMode}
import org.semanticweb.owlapi.apibinding.OWLFunctionalSyntaxFactory.{OWLNothing, OWLThing}
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model._
import org.semanticweb.owlapi.model.parameters.Imports
import zio._
import zio.blocking._
import zio.stream._

import scala.io.Source
import scala.jdk.CollectionConverters._

object Main extends ZCaseApp[Config] {

  private val RDFType = RDF.`type`.asNode
  private val RDFSSubClassOf = RDFS.subClassOf.asNode
  private val OWLEquivalentClass = OWL2.equivalentClass.asNode
  private val OWLRestriction = OWL2.Restriction.asNode
  private val OWLOnProperty = OWL2.onProperty.asNode
  private val OWLSomeValuesFrom = OWL2.someValuesFrom.asNode
  private val OWLOntology = OWL2.Ontology.asNode
  private val df = OWLManager.getOWLDataFactory

  override def run(config: Config, arg: RemainingArgs): ZIO[ZEnv, Nothing, ExitCode] = {
    val ontologyFile = new File(config.ontologyFile)
    val nonRedundantOutputFile = new File(config.nonRedundantOutputFile)
    val redundantOutputFile = new File(config.redundantOutputFile)
    val streamsManaged = for {
      nonredundantOutputStream <- Managed.fromAutoCloseable(ZIO.effect(new FileOutputStream(nonRedundantOutputFile)))
      redundantOutputStream <- Managed.fromAutoCloseable(ZIO.effect(new FileOutputStream(redundantOutputFile)))
      nonredundantRDFWriter <- createStreamRDF(nonredundantOutputStream)
      redundantRDFWriter <- createStreamRDF(redundantOutputStream)
    } yield (nonredundantRDFWriter, redundantRDFWriter)
    val program = streamsManaged.use {
      case (nonredundantRDFWriter, redundantRDFWriter) =>
        for {
          fileProperties <- config.propertiesFile.map(readPropertiesFile).getOrElse(ZIO.succeed(Set.empty[OWLObjectProperty]))
          specifiedProperties = fileProperties ++ config.property.map(prop => df.getOWLObjectProperty(IRI.create(prop))).to(Set)
          manager <- ZIO.effect(OWLManager.createOWLOntologyManager())
          ontology <- ZIO.effect(manager.loadOntologyFromOntologyDocument(ontologyFile))
          whelkOntology = Bridge.ontologyToAxioms(ontology)
          _ <- ZIO.effectTotal(scribe.info("Running reasoner"))
          whelk = Reasoner.assert(whelkOntology)
          _ <- ZIO.effectTotal(scribe.info("Done running reasoner"))
          _ <- (effectBlockingIO(
            nonredundantRDFWriter.triple(Triple.create(NodeFactory.createBlankNode("nonredundant"), RDFType, OWLOntology))) *>
            effectBlockingIO(redundantRDFWriter.triple(Triple.create(NodeFactory.createBlankNode("redundant"), RDFType, OWLOntology))))
            .when(config.mode == OWLMode)
          start <- ZIO.effectTotal(System.currentTimeMillis())
          classes = classHierarchy(whelk)
          properties = propertyHierarchy(ontology)
          classesStream = allClasses(ontology)
          classesTasks = classesStream.map(c => ZIO.effectTotal(processSuperclasses(c, whelk, config)))
          queue <- Queue.unbounded[Restriction]
          activeRestrictions <- Ref.make(0)
          _ <- traverse(properties, classes, whelk, config.mode, queue, activeRestrictions)
          restrictionsStream = Stream.fromQueue(queue).map(r => processRestrictionAndExtendQueue(r, classes, whelk, config.mode, queue, activeRestrictions))
          allTasks = classesTasks ++ restrictionsStream
          processed = allTasks.mapMParUnordered(JRuntime.getRuntime.availableProcessors)(identity)
          _ <- processed.foreach {
            case TriplesGroup(nonredundant, redundant) =>
              ZIO.effect {
                nonredundant.foreach(nonredundantRDFWriter.triple)
                redundant.foreach(redundantRDFWriter.triple)
              }
          }
          stop <- ZIO.effectTotal(System.currentTimeMillis())
          _ <- ZIO.effectTotal(scribe.info(s"Computed relations in ${(stop - start) / 1000.0}s"))
        } yield ()
    }
    program.exitCode
  }

  def readPropertiesFile(file: String): ZIO[Blocking, Throwable, Set[OWLObjectProperty]] =
    effectBlocking(Source.fromFile(file, "utf-8")).bracketAuto { source =>
      effectBlocking(source.getLines().map(_.trim).filter(_.nonEmpty).map(line => df.getOWLObjectProperty(IRI.create(line))).to(Set))
    }

  def createStreamRDF(output: OutputStream): Managed[Throwable, StreamRDF] =
    Managed.make {
      ZIO.effect {
        val stream = StreamRDFWriter.getWriterStream(output, RDFFormat.TURTLE_FLAT, null)
        stream.start()
        stream
      }
    }(stream => ZIO.effectTotal(stream.finish()))

  def allClasses(ont: OWLOntology): ZStream[Any, Nothing, OWLClass] = Stream.fromIterable(ont.getClassesInSignature(Imports.INCLUDED).asScala.to(Set) - OWLThing - OWLNothing)

  def traverse(properties: Hierarchy, classes: Hierarchy, reasoner: ReasonerState, mode: Config.OutputMode, queue: Queue[Restriction], activeRestrictions: Ref[Int]): UIO[Unit] =
    ZIO.foreach_(properties.subclasses.getOrElse(Top, Set.empty)) { subprop =>
      traverseProperty(subprop, properties, classes, reasoner, mode, queue, activeRestrictions)
    }

  def traverseProperty(property: AtomicConcept, properties: Hierarchy, classes: Hierarchy, whelk: ReasonerState, mode: Config.OutputMode, queue: Queue[Restriction], activeRestrictions: Ref[Int]): UIO[Unit] = {
    val restrictions = if (hasSubClasses(property, whelk)) {
      (classes.subclasses.getOrElse(Top, Set.empty) - Bottom).map(filler => Restriction(Role(property.id), filler))
    } else Set.empty
    for {
      _ <- activeRestrictions.update(current => current + restrictions.size)
      _ <- queue.offerAll(restrictions).unit
      active <- activeRestrictions.get
      _ <- queue.shutdown.when(active < 1)
    } yield ()

  }

  def hasSubClasses(property: AtomicConcept, whelk: ReasonerState): Boolean = {
    val queryConcept = AtomicConcept(s"${property.id}${Top.id}")
    val restriction = ExistentialRestriction(Role(property.id), Top)
    val axioms = Set(ConceptInclusion(queryConcept, restriction), ConceptInclusion(restriction, queryConcept))
    val updatedWhelk = Reasoner.assert(axioms, whelk)
    (updatedWhelk.closureSubsBySuperclass.getOrElse(queryConcept, Set.empty) - Bottom).nonEmpty
  }

  def processRestrictionAndExtendQueue(restriction: Restriction, classes: Hierarchy, whelk: ReasonerState, mode: Config.OutputMode, queue: Queue[Restriction], activeRestrictions: Ref[Int]): UIO[TriplesGroup] = {
    for {
      triples <- ZIO.effectTotal(processRestriction(restriction, whelk, mode))
      directFillerSubclassesRestrictions = if (triples.redundant.nonEmpty)
        (classes.subclasses.getOrElse(restriction.filler, Set.empty) - Bottom).map(c => Restriction(restriction.property, c))
      else Set.empty
      _ <- activeRestrictions.update(current => current - 1 + directFillerSubclassesRestrictions.size)
      _ <- queue.offerAll(directFillerSubclassesRestrictions)
      active <- activeRestrictions.get
      _ <- queue.shutdown.when(active < 1)
    } yield triples
  }

  def processRestriction(restriction: Restriction, whelk: ReasonerState, mode: Config.OutputMode): TriplesGroup = {
    val queryConcept = AtomicConcept(s"${restriction.property.id}${restriction.filler.id}")
    val er = ExistentialRestriction(restriction.property, restriction.filler)
    val axioms = Set(ConceptInclusion(queryConcept, er), ConceptInclusion(er, queryConcept))
    val updatedWhelk = Reasoner.assert(axioms, whelk)
    //FIXME don't create these if result is empty
    val predicate = NodeFactory.createURI(restriction.property.id)
    val target = NodeFactory.createURI(restriction.filler.id)
    val (equivalents, directSubclasses) = updatedWhelk.directlySubsumes(queryConcept)
    val subclasses =
      updatedWhelk.closureSubsBySuperclass(queryConcept).collect { case x: AtomicConcept => x } - queryConcept - Bottom
    if (!equivalents(BuiltIn.Bottom)) {
      val nonredundantTerms = directSubclasses - BuiltIn.Bottom ++ equivalents
      val nonredundantTriples = mode match {
        case RDFMode => nonredundantTerms.map(sc => Triple.create(NodeFactory.createURI(sc.id), predicate, target))
        case OWLMode => nonredundantTerms.flatMap(sc => owlTriples(NodeFactory.createURI(sc.id), predicate, target))
      }
      val redundantTriples = mode match {
        case RDFMode => subclasses.map(sc => Triple.create(NodeFactory.createURI(sc.id), predicate, target))
        case OWLMode => subclasses.flatMap(sc => owlTriples(NodeFactory.createURI(sc.id), predicate, target))
      }
      TriplesGroup(nonredundantTriples, redundantTriples)
    } else TriplesGroup.empty
  }

  def classHierarchy(reasoner: ReasonerState): Hierarchy = {
    val taxonomy = reasoner.computeTaxonomy
    val subclassTaxonomy = taxonomy.foldLeft(Map.empty[AtomicConcept, Set[AtomicConcept]]) { case (accum, (concept, (_, superclasses))) =>
      superclasses.foldLeft(accum) { case (inner, superclass) =>
        val updatedSubclasses = accum.getOrElse(superclass, Set.empty) + concept
        inner.updated(superclass, updatedSubclasses)
      }
    }
    val equivMap = taxonomy.map { case (concept, (equivs, _)) => concept -> equivs }
    Hierarchy(equivMap, subclassTaxonomy)
  }

  def propertyHierarchy(ont: OWLOntology): Hierarchy = {
    val subPropAxioms = ont.getAxioms(AxiomType.SUB_OBJECT_PROPERTY).asScala.to(Set).collect {
      case ax if ax.getSubProperty.isNamed && ax.getSuperProperty.isNamed => ConceptInclusion(
        AtomicConcept(ax.getSubProperty.asOWLObjectProperty.getIRI.toString),
        AtomicConcept(ax.getSuperProperty.asOWLObjectProperty.getIRI.toString))
    }
    val allProps = ont.getObjectPropertiesInSignature((Imports.INCLUDED)).asScala.to(Set).map(prop =>
      ConceptInclusion(AtomicConcept(prop.getIRI.toString), AtomicConcept(prop.getIRI.toString)))
    val allAxioms = (subPropAxioms ++ allProps).toSet[Axiom]
    val whelk = Reasoner.assert(allAxioms)
    classHierarchy(whelk)
  }

  def processSuperclasses(cls: OWLClass, whelk: ReasonerState, config: Config): TriplesGroup = {
    val subject = NodeFactory.createURI(cls.getIRI.toString)
    val concept = AtomicConcept(cls.getIRI.toString)
    val allSuperclasses = (whelk.closureSubsBySubclass.getOrElse(concept, Set.empty) - BuiltIn.Top)
      .collect { case ac @ AtomicConcept(_) => ac }
    if (allSuperclasses(BuiltIn.Bottom)) TriplesGroup.empty //unsatisfiable
    else {
      val (equivs, directSuperclasses) = whelk.directlySubsumedBy(concept)
      val adjustedEquivs = if (config.reflexiveSubclasses.bool) equivs + concept else equivs - concept
      val directSuperclassTriples = directSuperclasses.map(c => Triple.create(subject, RDFSSubClassOf, NodeFactory.createURI(c.id)))
      val equivalentClassTriples = if (config.equivalenceAsSubclass.bool)
        adjustedEquivs.map(c => Triple.create(subject, RDFSSubClassOf, NodeFactory.createURI(c.id)))
      else
        adjustedEquivs.map(c => Triple.create(subject, OWLEquivalentClass, NodeFactory.createURI(c.id)))
      val nonredundantTriples = directSuperclassTriples ++ equivalentClassTriples
      val adjustedSuperclasses = if (config.reflexiveSubclasses.bool) allSuperclasses + concept else allSuperclasses - concept
      val redundantTriples = if (config.equivalenceAsSubclass.bool)
        adjustedSuperclasses.map(c => Triple.create(subject, RDFSSubClassOf, NodeFactory.createURI(c.id)))
      else {
        val superclassesMinusEquiv = adjustedSuperclasses -- adjustedEquivs
        superclassesMinusEquiv.map(c => Triple.create(subject, RDFSSubClassOf, NodeFactory.createURI(c.id))) ++
          equivalentClassTriples
      }
      TriplesGroup(nonredundantTriples, redundantTriples)
    }
  }

  def owlTriples(subj: Node, pred: Node, obj: Node): Set[Triple] = {
    val hash = MessageDigest.getInstance("SHA-256").digest(s"$subj$pred$obj".getBytes(StandardCharsets.UTF_8))
    val id = Base64.getEncoder.encodeToString(hash)
    val restrictionNode = NodeFactory.createBlankNode(id)
    Set(
      Triple.create(subj, RDFSSubClassOf, restrictionNode),
      Triple.create(restrictionNode, RDFType, OWLRestriction),
      Triple.create(restrictionNode, OWLOnProperty, pred),
      Triple.create(restrictionNode, OWLSomeValuesFrom, obj)
    )
  }

  final case class Hierarchy(equivs: Map[AtomicConcept, Set[AtomicConcept]], subclasses: Map[AtomicConcept, Set[AtomicConcept]])

  final case class Restriction(property: Role, filler: AtomicConcept)

  final case class TriplesGroup(nonredundant: Set[Triple], redundant: Set[Triple])

  object TriplesGroup {

    val empty: TriplesGroup = TriplesGroup(Set.empty, Set.empty)

  }

}
