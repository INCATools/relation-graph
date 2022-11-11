package org.renci.relationgraph

import org.apache.jena.graph.Triple
import org.apache.jena.riot.system.StreamRDF
import org.apache.jena.shared.PrefixMapping
import org.apache.jena.sparql.core.Quad

import java.io.{File, PrintWriter}
import scala.jdk.CollectionConverters._

class TSVStreamRDF(file: File, prefixes: Map[String, String], oboPrefixes: Boolean) extends StreamRDF {

  private val prefixMapping = LongestFirstPrefixMapping(PrefixMapping.Standard.getNsPrefixMap.asScala.toMap ++ prefixes, oboPrefixes)

  private var writer: PrintWriter = _

  override def start(): Unit = {
    writer = new PrintWriter(file, "utf-8")
  }

  override def triple(triple: Triple): Unit = {
    val s = prefixMapping.compact(triple.getSubject.toString)
    val p = prefixMapping.compact(triple.getPredicate.toString)
    val o = prefixMapping.compact(triple.getObject.toString)
    writer.println(s"$s\t$p\t$o")
  }

  override def quad(quad: Quad): Unit = {
    val s = prefixMapping.compact(quad.getSubject.toString)
    val p = prefixMapping.compact(quad.getPredicate.toString)
    val o = prefixMapping.compact(quad.getObject.toString)
    val g = prefixMapping.compact(quad.getGraph.toString)
    writer.println(s"$s\t$p\t$o\t$g")
  }

  override def base(base: String): Unit = ()

  override def prefix(prefix: String, iri: String): Unit = ()

  override def finish(): Unit = {
    writer.close()
  }

}
