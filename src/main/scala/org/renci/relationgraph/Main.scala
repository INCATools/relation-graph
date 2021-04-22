package org.renci.relationgraph

import java.io.{File, FileOutputStream, OutputStream}
import java.lang.{Runtime => JRuntime}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

import caseapp._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.reactive._
import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.apache.jena.riot.RDFFormat
import org.apache.jena.riot.system.{StreamRDF, StreamRDFWriter}
import org.apache.jena.vocabulary.{OWL2, RDF, RDFS}
import org.geneontology.whelk._
import org.renci.relationgraph.Config.{OWLMode, RDFMode}
import org.semanticweb.owlapi.apibinding.OWLFunctionalSyntaxFactory.{OWLNothing, OWLThing}
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model._
import org.semanticweb.owlapi.model.parameters.Imports
import zio._
import zio.blocking._
import zio.interop.monix._

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
  private val OWLTopObjectProperty = df.getOWLTopObjectProperty

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
    val program = streamsManaged.use { case (nonredundantRDFWriter, redundantRDFWriter) =>
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
        classes = allClasses(ontology)
        classesTasks = classes.map(c => Task(processSuperclasses(c, whelk, config)))
        restrictions = extractAllRestrictions(ontology, specifiedProperties)
        restrictionsTasks = restrictions.map(r => Task(processRestriction(r, whelk, config.mode)))
        allTasks = classesTasks ++ restrictionsTasks
        processed = allTasks.mapParallelUnordered(JRuntime.getRuntime.availableProcessors)(identity)
        monixTask = processed.foreachL { case TriplesGroup(nonredundant, redundant) =>
          nonredundant.foreach(nonredundantRDFWriter.triple)
          redundant.foreach(redundantRDFWriter.triple)
        }
        _ <- IO.fromTask(monixTask)
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

  def allClasses(ont: OWLOntology): Observable[OWLClass] =
    Observable.fromIterable(ont.getClassesInSignature(Imports.INCLUDED).asScala.to(Set) - OWLThing - OWLNothing)

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
      val equivalentClassTriples =
        if (config.equivalenceAsSubclass.bool)
          adjustedEquivs.map(c => Triple.create(subject, RDFSSubClassOf, NodeFactory.createURI(c.id)))
        else
          adjustedEquivs.map(c => Triple.create(subject, OWLEquivalentClass, NodeFactory.createURI(c.id)))
      val nonredundantTriples = directSuperclassTriples ++ equivalentClassTriples
      val adjustedSuperclasses = if (config.reflexiveSubclasses.bool) allSuperclasses + concept else allSuperclasses - concept
      val redundantTriples =
        if (config.equivalenceAsSubclass.bool)
          adjustedSuperclasses.map(c => Triple.create(subject, RDFSSubClassOf, NodeFactory.createURI(c.id)))
        else {
          val superclassesMinusEquiv = adjustedSuperclasses -- adjustedEquivs
          superclassesMinusEquiv.map(c => Triple.create(subject, RDFSSubClassOf, NodeFactory.createURI(c.id))) ++
            equivalentClassTriples
        }
      TriplesGroup(nonredundantTriples, redundantTriples)
    }
  }

  def extractAllRestrictions(ont: OWLOntology, specifiedProperties: Set[OWLObjectProperty]): Observable[Restriction] = {
    val properties =
      if (specifiedProperties.nonEmpty) specifiedProperties
      else ont.getObjectPropertiesInSignature(Imports.INCLUDED).asScala.to(Set) - OWLTopObjectProperty
    val propertiesStream = Observable.fromIterable(properties)
    val classesStream = allClasses(ont)
    for {
      property <- propertiesStream
      cls <- classesStream
    } yield Restriction(property, cls)
  }

  def processRestriction(combo: Restriction, whelk: ReasonerState, mode: Config.OutputMode): TriplesGroup = {
    val Restriction(property, cls) = combo
    val propertyID = property.getIRI.toString
    val clsID = cls.getIRI.toString
    val queryConcept = AtomicConcept(s"$propertyID$clsID")
    val restriction = ExistentialRestriction(Role(propertyID), AtomicConcept(clsID))
    val axioms = Set(ConceptInclusion(queryConcept, restriction), ConceptInclusion(restriction, queryConcept))
    val updatedWhelk = Reasoner.assert(axioms, whelk)
    val predicate = NodeFactory.createURI(property.getIRI.toString)
    val target = NodeFactory.createURI(cls.getIRI.toString)
    val (equivalents, directSubclasses) = updatedWhelk.directlySubsumes(queryConcept)
    val subclasses =
      updatedWhelk.closureSubsBySuperclass(queryConcept).collect { case x: AtomicConcept => x } - queryConcept - BuiltIn.Bottom
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

  final case class Restriction(property: OWLObjectProperty, filler: OWLClass)

  final case class TriplesGroup(nonredundant: Set[Triple], redundant: Set[Triple])

  object TriplesGroup {

    val empty: TriplesGroup = TriplesGroup(Set.empty, Set.empty)

  }

}
