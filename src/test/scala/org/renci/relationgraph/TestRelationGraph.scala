package org.renci.relationgraph

import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.geneontology.whelk.{Bridge, Reasoner}
import org.renci.relationgraph.Main.{IndexedReasonerState, TriplesGroup}
import org.semanticweb.owlapi.apibinding.OWLManager
import zio._
import zio.duration.durationInt
import zio.test.Assertion._
import zio.test.TestAspect.timeout
import zio.test._

object TestRelationGraph extends DefaultRunnableSpec {

  private val Prefix = "http://example.org/test"
  private val P = NodeFactory.createURI(s"$Prefix#p")

  private def n: String => Node = NodeFactory.createURI

  def spec =
    suite("RelationGraphSpec")(
      testM("testMaterializedRelations") {
        for {
          manager <- ZIO.effect(OWLManager.createOWLOntologyManager())
          ontology <- ZIO.effect(manager.loadOntologyFromOntologyDocument(this.getClass.getResourceAsStream("materialize_test.ofn")))
          whelkOntology = Bridge.ontologyToAxioms(ontology)
          whelk = Reasoner.assert(whelkOntology)
          indexedWhelk = IndexedReasonerState(whelk)
          resultsStream = Main.computeRelations(ontology, indexedWhelk, Set.empty, true, false, false, true, false, Config.RDFMode)
          results <- resultsStream.runCollect
          triples <- ZIO.fromOption(results.reduceOption((left, right) => TriplesGroup(left.redundant ++ right.redundant)))
          TriplesGroup(redundant) = triples
        } yield
          assertTrue(redundant.contains(Triple.create(n(s"$Prefix#A"), P, n(s"$Prefix#D")))) &&
            assertTrue(redundant.contains(Triple.create(n(s"$Prefix#C"), P, n(s"$Prefix#D")))) &&
            assertTrue(redundant.contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#B")))) &&
            assertTrue(!(redundant.contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#C"))))) &&
            assertTrue(redundant.contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#C")))) &&
            assertTrue(redundant.contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#A"))))
      },
      (testM("exitProperlyWhenNoObjectPropertiesAreDeclared") {
        for {
          manager <- ZIO.effect(OWLManager.createOWLOntologyManager())
          ontology <- ZIO.effect(manager.loadOntologyFromOntologyDocument(this.getClass.getResourceAsStream("apo.owl")))
          whelkOntology = Bridge.ontologyToAxioms(ontology)
          whelk = Reasoner.assert(whelkOntology)
          indexedWhelk = IndexedReasonerState(whelk)
          resultsStream = Main.computeRelations(ontology, indexedWhelk, Set.empty, true, false, false, true, false, Config.RDFMode)
          results <- resultsStream.runCollect
          triples <- ZIO.fromOption(results.reduceOption((left, right) => TriplesGroup(left.redundant ++ right.redundant)))
          TriplesGroup(redundant) = triples
        } yield assertTrue(ontology.getObjectPropertiesInSignature().isEmpty) && assertTrue(redundant.nonEmpty)
      }) @@ timeout(5.seconds)
    )

}
