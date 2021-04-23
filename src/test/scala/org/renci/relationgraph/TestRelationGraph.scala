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

  def spec = ???

//  def spec =
//    suite("RelationGraphSpec") {
//      testM("testMaterializedRelations") {
//        for {
//          manager <- ZIO.effect(OWLManager.createOWLOntologyManager())
//          ontology <- ZIO.effect(manager.loadOntologyFromOntologyDocument(this.getClass.getResourceAsStream("materialize_test.ofn")))
//          restrictions = Main.extractAllRestrictions(ontology, Set.empty)
//          whelkOntology = Bridge.ontologyToAxioms(ontology)
//          whelk = Reasoner.assert(whelkOntology)
//          results <- restrictions.map(Main.processRestriction(_, whelk, Config.RDFMode)).runCollect
//          triples <- ZIO.fromOption(results.reduceOption((left, right) => TriplesGroup(left.nonredundant ++ right.nonredundant, left.redundant ++ right.redundant)))
//          TriplesGroup(nonredundant, redundant) = triples
//        } yield assert(nonredundant)(contains(Triple.create(n(s"$Prefix#A"), P, n(s"$Prefix#D")))) &&
//          assert(redundant)(contains(Triple.create(n(s"$Prefix#A"), P, n(s"$Prefix#D")))) &&
//          assert(nonredundant)(not(contains(Triple.create(n(s"$Prefix#C"), P, n(s"$Prefix#D"))))) &&
//          assert(redundant)(contains(Triple.create(n(s"$Prefix#C"), P, n(s"$Prefix#D")))) &&
//          assert(nonredundant)(contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#B")))) &&
//          assert(redundant)(contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#B")))) &&
//          assert(nonredundant)(not(contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#C"))))) &&
//          assert(redundant)(not(contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#C"))))) &&
//          assert(nonredundant)(contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#C")))) &&
//          assert(redundant)(contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#C")))) &&
//          assert(nonredundant)(not(contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#A"))))) &&
//          assert(redundant)(contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#A"))))
//      }
//    }

}
