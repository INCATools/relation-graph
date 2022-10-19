package org.renci.relationgraph

import caseapp._
import caseapp.core.Error.MalformedValue
import caseapp.core.argparser.{ArgParser, SimpleArgParser}
import org.renci.relationgraph.Config.{BoolValue, FalseValue, OutputMode, RDFMode, TrueValue}

@AppName("relation-graph")
@ProgName("relation-graph")
final case class Config(
                         @HelpMessage("Input OWL ontology")
                         @ValueDescription("filename")
                         ontologyFile: String,
                         @HelpMessage("File to stream output triples to.")
                         @ValueDescription("filename")
                         outputFile: String,
                         @HelpMessage("Configure style of triples to be output. RDF mode is the default; each existential relation is collapsed to a single direct triple. TSV mode outputs the same triples as RDF mode, but as TSV, compacting IRIs using an optional prefixes file.")
                         @ValueDescription("RDF|OWL|TSV")
                         mode: OutputMode = RDFMode,
                         @HelpMessage("Property to restrict output relations to. Provide option multiple times for multiple properties. If no properties are provided (via CLI or file), then all properties found in the ontology will be used.")
                         @ValueDescription("IRI")
                         property: List[String] = Nil,
                         @HelpMessage("File containing line-separated property IRIs to restrict output relations to. If no properties are provided (via CLI or file), then all properties found in the ontology will be used.")
                         @ValueDescription("filename")
                         propertiesFile: Option[String],
                         @HelpMessage("Include entailed rdfs:subClassOf or owl:equivalentClass relations in output (default false)")
                         @ValueDescription("bool")
                         outputSubclasses: BoolValue = FalseValue,
                         @HelpMessage("When outputting rdfs:subClassOf, include relations to self for every class (default true)")
                         @ValueDescription("bool")
                         reflexiveSubclasses: BoolValue = TrueValue,
                         @HelpMessage("When outputting equivalent classes, output reciprocal rdfs:subClassOf triples instead of owl:equivalentClass triples (default true)")
                         @ValueDescription("bool")
                         equivalenceAsSubclass: BoolValue = TrueValue,
                         @HelpMessage("Output any triples where classes are subjects (default true)")
                         @ValueDescription("bool")
                         outputClasses: BoolValue = TrueValue,
                         @HelpMessage("Output triples where individuals are subjects, with classes as objects (default false)")
                         @ValueDescription("bool")
                         outputIndividuals: BoolValue = FalseValue,
                         @HelpMessage("Disable inference of unsatisfiable classes by the whelk reasoner (default false)")
                         @ValueDescription("bool")
                         disableOwlNothing: BoolValue = FalseValue,
                         @HelpMessage("Prefix mappings to use for TSV output")
                         @ValueDescription("filename")
                         prefixes: Option[String],
                         @HelpMessage("Compact OBO-style IRIs regardless of inclusion in prefixes file")
                         @ValueDescription("bool")
                         oboPrefixes: BoolValue = TrueValue,
                         @HelpMessage("Set log level to INFO")
                         @ValueDescription("bool")
                         verbose: Boolean = false) {

  def toRelationGraphConfig: RelationGraph.Config =
    RelationGraph.Config(
      mode = this.mode match {
        case Config.RDFMode => RelationGraph.Config.RDFMode
        case Config.OWLMode => RelationGraph.Config.OWLMode
        case Config.TSVMode => RelationGraph.Config.RDFMode
      },
      outputSubclasses = this.outputSubclasses.bool,
      reflexiveSubclasses = this.reflexiveSubclasses.bool,
      equivalenceAsSubclass = this.equivalenceAsSubclass.bool,
      outputClasses = this.outputClasses.bool,
      outputIndividuals = this.outputIndividuals.bool,
      disableOwlNothing = this.disableOwlNothing.bool,
    )

}

object Config {

  sealed trait OutputMode

  case object RDFMode extends OutputMode

  case object OWLMode extends OutputMode

  case object TSVMode extends OutputMode

  implicit val rdfModeParser: ArgParser[OutputMode] = SimpleArgParser.from[OutputMode]("output mode") { arg =>
    arg.toLowerCase match {
      case "rdf" => Right(RDFMode)
      case "owl" => Right(OWLMode)
      case "tsv" => Right(TSVMode)
      case _     => Left(MalformedValue("output mode", arg))
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
