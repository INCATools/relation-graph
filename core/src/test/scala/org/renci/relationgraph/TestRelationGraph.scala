package org.renci.relationgraph

import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.geneontology.whelk.AtomicConcept
import org.renci.relationgraph.RelationGraph.Config.RDFMode
import org.renci.relationgraph.RelationGraph.{Config, TriplesGroup}
import org.semanticweb.owlapi.apibinding.OWLManager
import zio._
import zio.test.TestAspect.timeout
import zio.test._


object TestRelationGraph extends ZIOSpecDefault {

  private val Prefix = "http://example.org/test"
  private val P = NodeFactory.createURI(s"$Prefix#p")

  private def n: String => Node = NodeFactory.createURI

  private val testConfig = Config(
    mode = RDFMode,
    outputSubclasses = true,
    reflexiveSubclasses = false,
    equivalenceAsSubclass = false,
    outputClasses = true,
    outputIndividuals = false,
    disableOwlNothing = false
  )

  def spec =
    suite("RelationGraphSpec")(
      test("testMaterializedRelations") {
        for {
          manager <- ZIO.attempt(OWLManager.createOWLOntologyManager())
          ontology <- ZIO.attempt(manager.loadOntologyFromOntologyDocument(this.getClass.getResourceAsStream("materialize_test.ofn")))
          resultsStream = RelationGraph.computeRelations(ontology, Set.empty, testConfig)
          results <- resultsStream.runCollect
          triples <- ZIO.from(results.reduceOption((left, right) => TriplesGroup(left.redundant ++ right.redundant)))
          TriplesGroup(redundant) = triples
        } yield
          assertTrue(redundant.contains(Triple.create(n(s"$Prefix#A"), P, n(s"$Prefix#D")))) &&
            assertTrue(redundant.contains(Triple.create(n(s"$Prefix#C"), P, n(s"$Prefix#D")))) &&
            assertTrue(redundant.contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#B")))) &&
            assertTrue(!(redundant.contains(Triple.create(n(s"$Prefix#F"), P, n(s"$Prefix#C"))))) &&
            assertTrue(redundant.contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#C")))) &&
            assertTrue(redundant.contains(Triple.create(n(s"$Prefix#E"), P, n(s"$Prefix#A"))))
      },
      test("exitProperlyWhenNoObjectPropertiesAreDeclared") {
        for {
          manager <- ZIO.attempt(OWLManager.createOWLOntologyManager())
          ontology <- ZIO.attempt(manager.loadOntologyFromOntologyDocument(this.getClass.getResourceAsStream("apo.owl")))
          resultsStream = RelationGraph.computeRelations(ontology, Set.empty, testConfig)
          results <- resultsStream.runCollect
          triples <- ZIO.from(results.reduceOption((left, right) => TriplesGroup(left.redundant ++ right.redundant)))
          TriplesGroup(redundant) = triples
        } yield assertTrue(ontology.getObjectPropertiesInSignature().isEmpty) && assertTrue(redundant.nonEmpty)
      } @@ timeout(5.seconds),
      test("properlyHandleUndefinedRelation") {
        for {
          manager <- ZIO.attempt(OWLManager.createOWLOntologyManager())
          ontology <- ZIO.attempt(manager.loadOntologyFromOntologyDocument(this.getClass.getResourceAsStream("zfa.owl")))
          resultsStream = RelationGraph.computeRelations(ontology, Set(AtomicConcept("http://purl.obolibrary.org/obo/BFO_0000050"), AtomicConcept("http://purl.obolibrary.org/obo/nonexistent")), testConfig.copy(outputSubclasses = false))
          results <- resultsStream.runCollect
          triples <- ZIO.from(results.reduceOption((left, right) => TriplesGroup(left.redundant ++ right.redundant)))
          TriplesGroup(redundant) = triples
        } yield assertTrue(redundant.nonEmpty)
      }
    )

}
