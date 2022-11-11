package org.renci.relationgraph

case class LongestFirstPrefixMapping(prefixToNS: Map[String, String], oboPrefixes: Boolean) {

  private val nsToPrefix = prefixToNS.toList
    .map { case (prefix, ns) => (ns, prefix) }
    .sortBy { case (ns, _) => -ns.length }
  private val oboNS = "http://purl.obolibrary.org/obo/"

  def compactOpt(uri: String): Option[String] = {
    nsToPrefix.collectFirst { case (ns, prefix) if uri.startsWith(ns) =>
      val local = uri.substring(ns.length, uri.length)
      s"$prefix:$local"
    }
  }.orElse {
    if (oboPrefixes && uri.startsWith(oboNS)) {
      val id = uri.substring(oboNS.length, uri.length)
      Some(id.split("_", 2).mkString(":"))
    } else None
  }

  def compact(uri: String): String = compactOpt(uri).getOrElse(uri)

}
