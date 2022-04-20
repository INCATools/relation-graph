package org.renci.relationgraph

import caseapp._
import org.apache.jena.riot.RDFFormat
import org.apache.jena.riot.system.{StreamRDF, StreamRDFWriter}
import org.geneontology.whelk._
import org.renci.relationgraph.RelationGraph.TriplesGroup
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model._
import scribe.Level
import scribe.filter.{packageName, select}
import zio._
import Config._

import java.io.{File, FileOutputStream}
import scala.io.Source

object Main extends ZCaseApp[Config] {

  override def run(config: Config, arg: RemainingArgs): ZIO[Environment, Nothing, ExitCode] = {
    val configureLogging = ZIO.succeed {
      scribe.Logger.root
        .clearHandlers()
        .clearModifiers()
        .withModifier(select(packageName("org.renci.relationgraph")).boosted(Level.Info, Level.Warn))
        .withHandler(minimumLevel = Some(if (config.verbose) Level.Info else Level.Warn))
        .replace()
    }
    val program = ZIO.scoped {
      createStreamRDF(config.outputFile).flatMap { rdfWriter =>
        for {
          fileProperties <- config.propertiesFile.map(readPropertiesFile).getOrElse(ZIO.succeed(Set.empty[AtomicConcept]))
          specifiedProperties = fileProperties ++ config.property.map(prop => AtomicConcept(prop)).to(Set)
          ontology <- loadOntology(config.ontologyFile)
          _ <- RelationGraph.computeRelations(ontology, specifiedProperties, config.toRelationGraphConfig)
            .foreach {
              case TriplesGroup(triples) => ZIO.attempt(triples.foreach(rdfWriter.triple))
            }
          _ <- ZIO.succeed(scribe.info("Done computing relations"))
        } yield ()
      }
    }
    configureLogging *>
      program.tapError { e =>
        if (config.verbose) ZIO.succeed(e.printStackTrace())
        else ZIO.succeed(scribe.error(e.getMessage))
      }.exitCode
  }

  def createStreamRDF(path: String): ZIO[Scope, Throwable, StreamRDF] = {
    ZIO.acquireRelease(ZIO.attempt(new FileOutputStream(new File(path))))(stream => ZIO.succeed(stream.close())).flatMap { outputStream =>
      ZIO.acquireRelease(ZIO.attempt {
        val stream = StreamRDFWriter.getWriterStream(outputStream, RDFFormat.TURTLE_FLAT, null)
        stream.start()
        stream
      })(stream => ZIO.succeed(stream.finish()))
    }
  }

  def loadOntology(path: String): Task[OWLOntology] = for {
    manager <- ZIO.attempt(OWLManager.createOWLOntologyManager())
    ontology <- ZIO.attemptBlocking(manager.loadOntologyFromOntologyDocument(new File(path)))
  } yield ontology

  def readPropertiesFile(file: String): ZIO[Any, Throwable, Set[AtomicConcept]] =
    ZIO.attemptBlocking(Source.fromFile(file, "utf-8")).acquireReleaseWithAuto { source =>
      ZIO.attemptBlocking(source.getLines().map(_.trim).filter(_.nonEmpty).map(line => AtomicConcept(line)).to(Set))
    }

}
