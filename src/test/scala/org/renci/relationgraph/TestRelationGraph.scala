package org.renci.relationgraph

import monix.execution.Scheduler.Implicits.global
import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.geneontology.whelk.{Bridge, Reasoner}
import org.semanticweb.owlapi.apibinding.OWLManager
import zio._
import zio.interop.monix._
import zio.test.Assertion._
import zio.test._

object TestRelationGraph extends DefaultRunnableSpec {

  private val Prefix = "http://example.org/test"
  private val P = NodeFactory.createURI(s"$Prefix#p")
  private val Q = NodeFactory.createURI(s"$Prefix#q")

  private def n: String => Node = NodeFactory.createURI

  def spec =
    suite("RelationGraphSpec") {
      testM("testMaterializedRelations") {
        for {
          manager <- ZIO.effect(OWLManager.createOWLOntologyManager())
          ontology <- ZIO.effect(manager.loadOntologyFromOntologyDocument(this.getClass.getResourceAsStream("materialize_test.ofn")))
          restrictions = Main.extractAllRestrictions(ontology, Set.empty)
          whelkOntology = Bridge.ontologyToAxioms(ontology)
          whelk = Reasoner.assert(whelkOntology)
          triples <- IO.fromTask(
            restrictions
              .map(Main.processRestriction(_, whelk, Config.RDFMode))
              .reduce((left, right) => (left._1 ++ right._1, left._2 ++ right._2))
              .headL)
          (nonredundant, redundant) = triples
        } yield assert(nonredundant)(contains(Triple.create(n(s"$Prefix#A"), P, n(s"$Prefix#D")))) &&
          assert(redundant)(contains(Triple.create(n(s"$Prefix#A"), P, n(s"$Prefix#D")))) &&
          assert(nonredundant)(not(contains(Triple.create(n(s"$Prefix#C"), P, n(s"$Prefix#D"))))) &&
          assert(redundant)(contains(Triple.create(n(s"$Prefix#C"), P, n(s"$Prefix#D")))) &&
          assert(nonredundant)(contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#B")))) &&
          assert(redundant)(contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#B")))) &&
          assert(nonredundant)(not(contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#C"))))) &&
          assert(redundant)(not(contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#C"))))) &&
          assert(nonredundant)(contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#C")))) &&
          assert(redundant)(contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#C")))) &&
          assert(nonredundant)(not(contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#A"))))) &&
          assert(redundant)(contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#A")))) &&
          assert(redundant)(contains(Triple.create(n(s"$Prefix#T"), Q, n(s"$Prefix#Z")))) &&
          assert(nonredundant)(not(contains(Triple.create(n(s"$Prefix#T"), Q, n(s"$Prefix#Z"))))) &&
          assert(redundant)(contains(Triple.create(n(s"$Prefix#V"), Q, n(s"$Prefix#Z")))) &&
          assert(nonredundant)(not(contains(Triple.create(n(s"$Prefix#V"), Q, n(s"$Prefix#Z")))))
      }
    }

}
