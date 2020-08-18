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

import scala.jdk.CollectionConverters._

object Main extends ZCaseApp[Config] {

  private val RDFType = RDF.`type`.asNode
  private val RDFSSubClassOf = RDFS.subClassOf.asNode
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
    val specifiedProperties = config.properties.map(prop => df.getOWLObjectProperty(IRI.create(prop))).to(Set)
    val streamsManaged = for {
      nonredundantOutputStream <- Managed.fromAutoCloseable(ZIO.effect(new FileOutputStream(nonRedundantOutputFile)))
      redundantOutputStream <- Managed.fromAutoCloseable(ZIO.effect(new FileOutputStream(redundantOutputFile)))
      nonredundantRDFWriter <- createStreamRDF(nonredundantOutputStream)
      redundantRDFWriter <- createStreamRDF(redundantOutputStream)
    } yield (nonredundantRDFWriter, redundantRDFWriter)
    val program = streamsManaged.use {
      case (nonredundantRDFWriter, redundantRDFWriter) =>
        for {
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
          restrictions = extractAllRestrictions(ontology, specifiedProperties)
          processed =
            restrictions.mapParallelUnordered(JRuntime.getRuntime.availableProcessors)(r => Task(processRestriction(r, whelk, config.mode)))
          monixTask = processed.foreachL {
            case (nonredundant, redundant) =>
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

  def createStreamRDF(output: OutputStream): Managed[Throwable, StreamRDF] =
    Managed.make {
      ZIO.effect {
        val stream = StreamRDFWriter.getWriterStream(output, RDFFormat.TURTLE_FLAT, null)
        stream.start()
        stream
      }
    }(stream => ZIO.effectTotal(stream.finish()))

  def extractAllRestrictions(ont: OWLOntology, specifiedProperties: Set[OWLObjectProperty]): Observable[Restriction] = {
    val properties =
      if (specifiedProperties.nonEmpty) specifiedProperties
      else ont.getObjectPropertiesInSignature(Imports.INCLUDED).asScala.to(Set) - OWLTopObjectProperty
    val transitiveProperties =
      ont.getAxioms(AxiomType.TRANSITIVE_OBJECT_PROPERTY, Imports.INCLUDED).asScala.to(Set).map(_.getProperty).collect {
        case p: OWLObjectProperty => p
      }
    val classes = ont.getClassesInSignature(Imports.INCLUDED).asScala.to(Set) - OWLThing - OWLNothing
    val propertiesStream = Observable.fromIterable(properties)
    val classesStream = Observable.fromIterable(classes)
    for {
      property <- propertiesStream
      cls <- classesStream
    } yield Restriction(property, cls, transitiveProperties(property))
  }

  def processRestriction(combo: Restriction, whelk: ReasonerState, mode: Config.OutputMode): (Set[Triple], Set[Triple]) = {
    val Restriction(property, cls, isTransitive) = combo
    val propertyID = property.getIRI.toString
    val clsID = cls.getIRI.toString
    val role = Role(propertyID)
    val (queryConcept, axioms) = createQueryConcept(role, AtomicConcept(clsID))
    val updatedWhelk = Reasoner.assert(axioms, whelk)
    val predicate = NodeFactory.createURI(property.getIRI.toString)
    val target = NodeFactory.createURI(cls.getIRI.toString)
    val (equivalents, directSubclasses) = updatedWhelk.directlySubsumes(queryConcept)
    val subclasses =
      updatedWhelk.closureSubsBySuperclass(queryConcept).collect { case x: AtomicConcept => x } - queryConcept - BuiltIn.Bottom
    if (!equivalents(BuiltIn.Bottom)) {
      val directSubs = if (isTransitive) {
        val (subQueryConcepts, subQueryAxioms) = directSubclasses.map(c => createQueryConcept(role, c)).unzip
        val subWhelk = Reasoner.assert(subQueryAxioms.flatten, updatedWhelk)
        val (_, newDirectSubclasses) = subWhelk.directlySubsumes(queryConcept)
        newDirectSubclasses -- subQueryConcepts
      } else directSubclasses
      val nonredundantTerms = directSubs - BuiltIn.Bottom ++ equivalents
      val nonredundantTriples = mode match {
        case RDFMode => nonredundantTerms.map(sc => Triple.create(NodeFactory.createURI(sc.id), predicate, target))
        case OWLMode => nonredundantTerms.flatMap(sc => owlTriples(NodeFactory.createURI(sc.id), predicate, target))
      }
      val redundantTriples = mode match {
        case RDFMode => subclasses.map(sc => Triple.create(NodeFactory.createURI(sc.id), predicate, target))
        case OWLMode => subclasses.flatMap(sc => owlTriples(NodeFactory.createURI(sc.id), predicate, target))
      }
      (nonredundantTriples, redundantTriples)
    } else (Set.empty[Triple], Set.empty[Triple])
  }

  def createQueryConcept(role: Role, concept: AtomicConcept): (AtomicConcept, Set[ConceptInclusion]) = {
    val queryConcept = AtomicConcept(s"${role.id}${concept.id}")
    val restriction = ExistentialRestriction(role, concept)
    val axioms = Set(ConceptInclusion(queryConcept, restriction), ConceptInclusion(restriction, queryConcept))
    (queryConcept, axioms)
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

  final case class Restriction(property: OWLObjectProperty, filler: OWLClass, transitive: Boolean)

}
