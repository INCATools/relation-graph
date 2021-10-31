package org.renci.relationgraph

import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.geneontology.whelk.{Bridge, Reasoner}
import org.renci.relationgraph.Main.TriplesGroup
import org.semanticweb.owlapi.apibinding.OWLManager
import zio._
import zio.test.Assertion._
import zio.test._

object TestRelationGraph extends DefaultRunnableSpec {

  private val Prefix = "http://example.org/test"
  private val P = NodeFactory.createURI(s"$Prefix#p")

  private def n: String => Node = NodeFactory.createURI

  def spec =
    suite("RelationGraphSpec") {
      testM("testMaterializedRelations") {
        for {
          manager <- ZIO.effect(OWLManager.createOWLOntologyManager())
          ontology <- ZIO.effect(manager.loadOntologyFromOntologyDocument(this.getClass.getResourceAsStream("materialize_test.ofn")))
          whelkOntology = Bridge.ontologyToAxioms(ontology)
          whelk = Reasoner.assert(whelkOntology)
          resultsStream = Main.computeRelations(ontology, whelk, Set.empty, true, false, false, Config.RDFMode)
          results <- resultsStream.runCollect
          triples <- ZIO.fromOption(results.reduceOption((left, right) => TriplesGroup(left.redundant ++ right.redundant)))
          TriplesGroup(redundant) = triples
        } yield
          assert(redundant)(contains(Triple.create(n(s"$Prefix#A"), P, n(s"$Prefix#D")))) &&
            assert(redundant)(contains(Triple.create(n(s"$Prefix#C"), P, n(s"$Prefix#D")))) &&
            assert(redundant)(contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#B")))) &&
            assert(redundant)(not(contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#C"))))) &&
            assert(redundant)(contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#C")))) &&
            assert(redundant)(contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#A"))))
      }
    }

}
