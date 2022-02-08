package org.renci.relationgraph

import caseapp.core.Error.MalformedValue
import caseapp.core.argparser.{ArgParser, SimpleArgParser}
import org.renci.relationgraph.Config.{BoolValue, FalseValue, TrueValue}

final case class Config(ontologyFile: String,
                        outputFile: String,
                        mode: Config.OutputMode = Config.RDFMode,
                        property: List[String] = Nil,
                        propertiesFile: Option[String],
                        outputSubclasses: BoolValue = FalseValue,
                        reflexiveSubclasses: BoolValue = TrueValue,
                        equivalenceAsSubclass: BoolValue = TrueValue,
                        disableOwlNothing: BoolValue = FalseValue,
                        verbose: Boolean = false)

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

  /**
   * This works around some confusing behavior in case-app boolean parsing
   */
  sealed trait BoolValue {

    def bool: Boolean

  }

  case object TrueValue extends BoolValue {

    def bool = true

  }

  case object FalseValue extends BoolValue {

    def bool = false

  }

  implicit val argParser: ArgParser[BoolValue] = SimpleArgParser.from[BoolValue]("boolean value") { arg =>
    arg.toLowerCase match {
      case "true"  => Right(TrueValue)
      case "false" => Right(FalseValue)
      case "1"     => Right(TrueValue)
      case "0"     => Right(FalseValue)
      case _       => Left(MalformedValue("boolean value", arg))
    }
  }

}
