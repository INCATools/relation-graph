package org.renci.relationgraph

import caseapp._
import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.apache.jena.riot.RDFFormat
import org.apache.jena.riot.system.{StreamRDF, StreamRDFWriter}
import org.apache.jena.sys.JenaSystem
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

import java.io.{File, FileOutputStream, OutputStream}
import java.lang.{Runtime => JRuntime}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import scala.io.Source
import scala.jdk.CollectionConverters._

object Main extends ZCaseApp[Config] {

  JenaSystem.init()

  private val RDFType = RDF.`type`.asNode
  private val RDFSSubClassOf = RDFS.subClassOf.asNode
  private val OWLEquivalentClass = OWL2.equivalentClass.asNode
  private val OWLRestriction = OWL2.Restriction.asNode
  private val OWLOnProperty = OWL2.onProperty.asNode
  private val OWLSomeValuesFrom = OWL2.someValuesFrom.asNode
  private val OWLOntology = OWL2.Ontology.asNode

  override def run(config: Config, arg: RemainingArgs): ZIO[ZEnv, Nothing, ExitCode] = {
    val streamManaged = for {
      outputStream <- Managed.fromAutoCloseable(ZIO.effect(new FileOutputStream(new File(config.outputFile))))
      rdfWriter <- createStreamRDF(outputStream)
    } yield rdfWriter
    val program = streamManaged.use { rdfWriter =>
      for {
        fileProperties <- config.propertiesFile.map(readPropertiesFile).getOrElse(ZIO.succeed(Set.empty[AtomicConcept]))
        specifiedProperties = fileProperties ++ config.property.map(prop => AtomicConcept(prop)).to(Set)
        ontology <- loadOntology(config.ontologyFile)
        whelkOntology = Bridge.ontologyToAxioms(ontology)
        _ <- ZIO.succeed(scribe.info("Running reasoner"))
        whelk = Reasoner.assert(whelkOntology)
        _ <- ZIO.succeed(scribe.info("Done running reasoner"))
        _ <- ZIO.fail(new Exception("Ontology is incoherent; please correct unsatisfiable classes.")).when(isIncoherent(whelk))
        _ <- effectBlockingIO(rdfWriter.triple(Triple.create(NodeFactory.createBlankNode("redundant"), RDFType, OWLOntology)))
          .when(config.mode == OWLMode)
        start <- ZIO.succeed(System.currentTimeMillis())
        triplesStream = computeRelations(ontology, whelk, specifiedProperties, config.outputSubclasses.bool, config.reflexiveSubclasses.bool, config.equivalenceAsSubclass.bool, config.mode)
        _ <- triplesStream.foreach {
          case TriplesGroup(triples) => ZIO.effect(triples.foreach(rdfWriter.triple))
        }
        stop <- ZIO.succeed(System.currentTimeMillis())
        _ <- ZIO.succeed(scribe.info(s"Computed relations in ${(stop - start) / 1000.0}s"))
      } yield ()
    }
    program.exitCode
  }

  def computeRelations(ontology: OWLOntology, whelk: ReasonerState, specifiedProperties: Set[AtomicConcept], outputSubclasses: Boolean, reflexiveSubclasses: Boolean, equivalenceAsSubclass: Boolean, mode: Config.OutputMode): UStream[TriplesGroup] = {
    val classes = classHierarchy(whelk)
    val properties = propertyHierarchy(ontology)
    val classesTasks = if (outputSubclasses) {
      allClasses(ontology).map(c => ZIO.succeed(processSuperclasses(c, whelk, reflexiveSubclasses, equivalenceAsSubclass)))
    } else Stream.empty
    val streamZ = for {
      queue <- Queue.unbounded[Restriction]
      activeRestrictions <- Ref.make(0)
      seenRef <- Ref.make(Map.empty[AtomicConcept, Set[AtomicConcept]])
      _ <- traverse(specifiedProperties, properties, classes, queue, activeRestrictions, seenRef)
      restrictionsStream = Stream.fromQueue(queue).map(r => processRestrictionAndExtendQueue(r, properties, classes, whelk, mode, specifiedProperties.isEmpty, queue, activeRestrictions, seenRef))
      allTasks = classesTasks ++ restrictionsStream
    } yield allTasks.mapMParUnordered(JRuntime.getRuntime.availableProcessors)(identity)
    Stream.unwrap(streamZ)
  }

  def readPropertiesFile(file: String): ZIO[Blocking, Throwable, Set[AtomicConcept]] =
    effectBlocking(Source.fromFile(file, "utf-8")).bracketAuto { source =>
      effectBlocking(source.getLines().map(_.trim).filter(_.nonEmpty).map(line => AtomicConcept(line)).to(Set))
    }

  def loadOntology(path: String): Task[OWLOntology] = for {
    manager <- ZIO.effect(OWLManager.createOWLOntologyManager())
    ontology <- ZIO.effect(manager.loadOntologyFromOntologyDocument(new File(path)))
  } yield ontology

  def createStreamRDF(output: OutputStream): Managed[Throwable, StreamRDF] =
    Managed.make {
      ZIO.effect {
        val stream = StreamRDFWriter.getWriterStream(output, RDFFormat.TURTLE_FLAT, null)
        stream.start()
        stream
      }
    }(stream => ZIO.succeed(stream.finish()))

  def allClasses(ont: OWLOntology): ZStream[Any, Nothing, OWLClass] = Stream.fromIterable(ont.getClassesInSignature(Imports.INCLUDED).asScala.to(Set) - OWLThing - OWLNothing)

  def traverse(specifiedProperties: Set[AtomicConcept], properties: Hierarchy, classes: Hierarchy, queue: Queue[Restriction], activeRestrictions: Ref[Int], seenRef: Ref[Map[AtomicConcept, Set[AtomicConcept]]]): UIO[Unit] = {
    val descendProperties = specifiedProperties.isEmpty
    val queryProperties = if (descendProperties) properties.subclasses.getOrElse(Top, Set.empty) - Bottom else specifiedProperties
    ZIO.foreachPar_(queryProperties) { subprop =>
      traverseProperty(subprop, classes, queue, activeRestrictions, seenRef)
    }
  }

  def traverseProperty(property: AtomicConcept, classes: Hierarchy, queue: Queue[Restriction], activeRestrictions: Ref[Int], seenRef: Ref[Map[AtomicConcept, Set[AtomicConcept]]]): UIO[Unit] = {
    val restrictions = (classes.subclasses.getOrElse(Top, Set.empty) - Bottom).map(filler => Restriction(Role(property.id), filler))
    for {
      _ <- seenRef.update { seen =>
        val seenForThisProperty = seen.getOrElse(property, Set.empty) ++ restrictions.map(_.filler)
        seen.updated(property, seenForThisProperty)
      }
      _ <- activeRestrictions.update(current => current + restrictions.size)
      _ <- queue.offerAll(restrictions).unit
      active <- activeRestrictions.get
      _ <- queue.shutdown.when(active < 1)
    } yield ()
  }

  def processRestrictionAndExtendQueue(restriction: Restriction, properties: Hierarchy, classes: Hierarchy, whelk: ReasonerState, mode: Config.OutputMode, descendProperties: Boolean, queue: Queue[Restriction], activeRestrictions: Ref[Int], seenRef: Ref[Map[AtomicConcept, Set[AtomicConcept]]]): UIO[TriplesGroup] = {
    val triples = processRestriction(restriction, whelk, mode)
    for {
      directFillerSubclassesRestrictions <- if (triples.redundant.nonEmpty) seenRef.modify { seen =>
        val propertyConcept = AtomicConcept(restriction.property.id)
        val seenForThisProperty = seen.getOrElse(propertyConcept, Set.empty)
        val subClasses = classes.subclasses.getOrElse(restriction.filler, Set.empty) - Bottom
        val unseenSubClasses = subClasses -- seenForThisProperty
        val updatedSeen = seen.updated(propertyConcept, seenForThisProperty ++ subClasses)
        val newRestrictions = unseenSubClasses.map(c => Restriction(restriction.property, c))
        if (descendProperties) {
          val subProperties = properties.subclasses.getOrElse(propertyConcept, Set.empty) - Bottom
          subProperties.foldLeft((newRestrictions, updatedSeen)) { case ((accRestrictions, accSeen), subProperty) =>
            val seenClassesForSubProperty = accSeen.getOrElse(subProperty, Set.empty)
            val updatedRestrictions = if (!seenClassesForSubProperty(restriction.filler))
              accRestrictions + Restriction(Role(subProperty.id), restriction.filler)
            else accRestrictions
            val updatedAccSeen = accSeen.updated(subProperty, seenClassesForSubProperty + restriction.filler)
            (updatedRestrictions, updatedAccSeen)
          }
        } else (newRestrictions, updatedSeen)
      } else ZIO.succeed(Set.empty[Restriction])
      _ <- activeRestrictions.update(current => current - 1 + directFillerSubclassesRestrictions.size)
      _ <- queue.offerAll(directFillerSubclassesRestrictions)
      active <- activeRestrictions.get
      _ <- queue.shutdown.when(active < 1)
    } yield triples
  }

  def processRestriction(restriction: Restriction, whelk: ReasonerState, mode: Config.OutputMode): TriplesGroup = {
    val subclasses = queryExistentialSubclasses(restriction, whelk)
    if (subclasses.nonEmpty) {
      val predicate = NodeFactory.createURI(restriction.property.id)
      val target = NodeFactory.createURI(restriction.filler.id)
      val outputTriples = mode match {
        case RDFMode => subclasses.map(sc => Triple.create(NodeFactory.createURI(sc.id), predicate, target))
        case OWLMode => subclasses.flatMap(sc => owlTriples(NodeFactory.createURI(sc.id), predicate, target))
      }
      TriplesGroup(outputTriples)
    } else TriplesGroup.empty
  }

  def queryExistentialSubclasses(restriction: Restriction, whelk: ReasonerState): Set[AtomicConcept] = {
    val queryConcept = AtomicConcept(s"${restriction.property.id}${restriction.filler.id}")
    val er = ExistentialRestriction(restriction.property, restriction.filler)
    val axioms = Set(ConceptInclusion(queryConcept, er), ConceptInclusion(er, queryConcept))
    val updatedWhelk = Reasoner.assert(axioms, whelk)
    updatedWhelk.closureSubsBySuperclass(queryConcept).collect { case x: AtomicConcept => x } - queryConcept - Bottom
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
      case ax if ax.getSubProperty.isNamed && ax.getSuperProperty.isNamed && !ax.getSuperProperty.isOWLTopObjectProperty => ConceptInclusion(
        AtomicConcept(ax.getSubProperty.asOWLObjectProperty.getIRI.toString),
        AtomicConcept(ax.getSuperProperty.asOWLObjectProperty.getIRI.toString))
    }
    val allProps = ont.getObjectPropertiesInSignature(Imports.INCLUDED).asScala.to(Set)
      .filterNot(_.isOWLTopObjectProperty)
      .map(prop =>
        ConceptInclusion(AtomicConcept(prop.getIRI.toString), AtomicConcept(prop.getIRI.toString)))
    val allAxioms = (subPropAxioms ++ allProps).toSet[Axiom]
    val whelk = Reasoner.assert(allAxioms)
    classHierarchy(whelk)
  }

  def processSuperclasses(cls: OWLClass, whelk: ReasonerState, reflexiveSubclasses: Boolean, equivalenceAsSubclass: Boolean): TriplesGroup = {
    val subject = NodeFactory.createURI(cls.getIRI.toString)
    val concept = AtomicConcept(cls.getIRI.toString)
    val allSuperclasses = (whelk.closureSubsBySubclass.getOrElse(concept, Set.empty) - BuiltIn.Top)
      .collect { case ac @ AtomicConcept(_) => ac }
    if (allSuperclasses(BuiltIn.Bottom)) TriplesGroup.empty //unsatisfiable
    else {
      val (equivs, _) = whelk.directlySubsumedBy(concept)
      val adjustedEquivs = if (reflexiveSubclasses) equivs + concept else equivs - concept
      val equivalentClassTriples = if (equivalenceAsSubclass)
        adjustedEquivs.map(c => Triple.create(subject, RDFSSubClassOf, NodeFactory.createURI(c.id)))
      else
        adjustedEquivs.map(c => Triple.create(subject, OWLEquivalentClass, NodeFactory.createURI(c.id)))
      val adjustedSuperclasses = if (reflexiveSubclasses) allSuperclasses + concept else allSuperclasses - concept
      val outputTriples = if (equivalenceAsSubclass)
        adjustedSuperclasses.map(c => Triple.create(subject, RDFSSubClassOf, NodeFactory.createURI(c.id)))
      else {
        val superclassesMinusEquiv = adjustedSuperclasses -- adjustedEquivs
        superclassesMinusEquiv.map(c => Triple.create(subject, RDFSSubClassOf, NodeFactory.createURI(c.id))) ++
          equivalentClassTriples
      }
      TriplesGroup(outputTriples)
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

  private def isIncoherent(state: ReasonerState): Boolean =
    state.closureSubsBySuperclass(Bottom).exists(t => !t.isAnonymous && t != Bottom)

  final case class Hierarchy(equivs: Map[AtomicConcept, Set[AtomicConcept]], subclasses: Map[AtomicConcept, Set[AtomicConcept]])

  final case class Restriction(property: Role, filler: AtomicConcept)

  final case class TriplesGroup(redundant: Set[Triple])

  object TriplesGroup {

    val empty: TriplesGroup = TriplesGroup(Set.empty)

  }

}
