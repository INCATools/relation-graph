package org.renci.relationgraph

import org.apache.jena.graph.Triple
import org.apache.jena.riot.system.StreamRDF
import org.apache.jena.shared.PrefixMapping
import org.apache.jena.shared.impl.PrefixMappingImpl
import org.apache.jena.sparql.core.Quad

import java.io.{File, PrintWriter}
import scala.jdk.CollectionConverters._

class TSVStreamRDF(file: String, prefixes: Option[Map[String, String]]) extends StreamRDF {

  private val prefixMapping: PrefixMapping = {
    val pm = new PrefixMappingImpl() {
      override def shortForm(uri: String): String = {
        val shortForm = super.shortForm(uri)
        if ((shortForm == uri) && (uri.startsWith("http://purl.obolibrary.org/obo/"))) {
          val tail = uri.replace("http://purl.obolibrary.org/obo/", "")
          tail.split("_", 2).mkString(":")
        } else shortForm
      }
    }
    pm.setNsPrefixes(prefixes.getOrElse(Map.empty).asJava)
      .withDefaultMappings(PrefixMapping.Standard)
  }

  private var writer: PrintWriter = _

  override def start(): Unit = {
    writer = new PrintWriter(new File(file), "utf-8")
  }

  override def triple(triple: Triple): Unit = {
    val s = triple.getSubject.toString(prefixMapping, true)
    val p = triple.getPredicate.toString(prefixMapping, true)
    val o = triple.getObject.toString(prefixMapping, true)
    writer.println(s"$s\t$p\t$o")
  }

  override def quad(quad: Quad): Unit = {
    val s = quad.getSubject.toString(prefixMapping, true)
    val p = quad.getPredicate.toString(prefixMapping, true)
    val o = quad.getObject.toString(prefixMapping, true)
    val g = quad.getGraph.toString(prefixMapping, true)
    writer.println(s"$s\t$p\t$o\t$g")
  }

  override def base(base: String): Unit = ???

  override def prefix(prefix: String, iri: String): Unit = ???

  override def finish(): Unit = {
    writer.close()
  }

}
