package org.renci.relationgraph

import org.apache.jena.vocabulary.{OWL, RDFS}
import org.geneontology.whelk.AtomicConcept
import org.phenoscape.scowl._
import org.renci.relationgraph.RelationGraph.Config
import org.renci.relationgraph.RelationGraph.Config.RDFMode
import org.semanticweb.owlapi.model.{IRI, OWLClassAxiom, OWLOntology}
import zio._

import java.util
import scala.jdk.CollectionConverters._

/**
 * Static methods supporting easier, synchronous, access from Java
 */
object RelationGraphUtil {

  private val RDFSSubClassOf = RDFS.subClassOf.getURI
  private val OWLEquivalentClass = OWL.equivalentClass.getURI

  /**
   * @param ontology
   * @param specifiedProperties properties for which to compute relations; an empty collection signifies all
   * @param outputConfig configuration for RelationGraph; `mode` is ignored, since results are converted to OWL axioms
   * @return
   */
  def computeRelationGraph(ontology: OWLOntology, specifiedProperties: util.Collection[IRI], outputConfig: Config): util.Set[OWLClassAxiom] = {
    val properties = specifiedProperties.asScala.to(Set).map(iri => AtomicConcept(iri.toString))
    val owlZ = RelationGraph.computeRelations(ontology, properties, outputConfig.copy(mode = RDFMode))
      .map { triplesGroup =>
        val triples = triplesGroup.redundant
        triples.map { triple =>
          triple.getPredicate.getURI match {
            case RDFSSubClassOf     => SubClassOf(Class(triple.getSubject.getURI), Class(triple.getObject.getURI))
            case OWLEquivalentClass => EquivalentClasses(Class(triple.getSubject.getURI), Class(triple.getObject.getURI))
            case property           => SubClassOf(Class(triple.getSubject.getURI), ObjectSomeValuesFrom(ObjectProperty(property), Class(triple.getObject.getURI)))
          }
        }
      }
      .runCollect
      .map(_.toSet.flatten)
      .map(_.asJava)
    Runtime.default.unsafeRun(owlZ)
  }

}
