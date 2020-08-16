package org.renci.relationgraph

import caseapp.core.Error.MalformedValue
import caseapp.core.argparser.{ArgParser, SimpleArgParser}

final case class Config(ontologyFile: String,
                        nonRedundantOutputFile: String,
                        redundantOutputFile: String,
                        mode: Config.OutputMode = Config.RDFMode,
                        properties: List[String] = Nil)

object Config {

  sealed trait OutputMode

  case object RDFMode extends OutputMode

  case object OWLMode extends OutputMode

  object OutputMode {

    implicit val argParser: ArgParser[OutputMode] = SimpleArgParser.from[OutputMode]("output mode") { arg =>
      arg.toLowerCase match {
        case "rdf" => Right(RDFMode)
        case "owl" => Right(OWLMode)
        case _     => Left(MalformedValue("output mode", arg))
      }
    }

  }

}
