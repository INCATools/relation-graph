# relation-graph

Materialize OWL existential relations

## Run

```bash
relation-graph --ontology-file uberon.owl --output-file relations.ttl --mode rdf --property 'http://purl.obolibrary.org/obo/BFO_0000050' --property 'http://purl.obolibrary.org/obo/BFO_0000051' --properties-file more_properties.txt
```

You can leave off the `property` and `properties-file` arguments; in that case all OWL object properties are used. The default mode is `rdf`; 
alternatively, the `owl` mode will output RDF, but as a valid OWL structure. The `properties-file` should look like this:

```
http://purl.obolibrary.org/obo/BFO_0000050
http://purl.obolibrary.org/obo/BFO_0000051
```
**Note:** Before running, you may need to increase the amount of memory available for Java. On UNIX style systems, this is done by setting the `JAVA_OPTS` environment variable. For example, to set the amount of memory to `16G`, you would use this command: `export JAVA_OPTS=-Xmx16G`.

### Full options

```
Usage: relation-graph [options]
  --usage  <bool>
        Print usage and exit
  --help | -h  <bool>
        Print help message and exit
  --version 
        Print release info and exit
  --ontology-file  <filename>
        Input OWL ontology
  --output-file  <filename>
        File to stream output triples to.
  --mode  <RDF|OWL>
        Configure style of triples to be output. RDF mode is the default; each existential relation is collapsed to a single direct triple.
  --property  <IRI>
        Property to restrict output relations to. Provide option multiple times for multiple properties.
  --properties-file  <filename>
        File containing line-separated property IRIs to restrict output relations to.
  --output-subclasses  <bool>
        Include entailed rdfs:subClassOf or owl:equivalentClass relations in output (default false)
  --reflexive-subclasses  <bool>
        When outputting rdfs:subClassOf, include relations to self for every class (default true)
  --equivalence-as-subclass  <bool>
        When outputting equivalent classes, output reciprocal rdfs:subClassOf triples instead of owl:equivalentClass triples (default true)
  --output-classes  <bool>
        Output any triples where classes are subjects (default true)
  --output-individuals  <bool>
        Output triples where individuals are subjects, with classes as objects (default false)
  --disable-owl-nothing  <bool>
        Disable inference of unsatisfiable classes by the whelk reasoner (default false)
  --verbose  <bool>
        Set log level to INFO
```

## Installation
### MacOS
1. Download `relation-graph-cli-*.tgz` from the [latest release](https://github.com/balhoff/relation-graph/releases/latest).
2. Unzip `relation-graph-cli-*.tgz`.
3. Run:
```
cd path/to/unzipped/relation-graph-cli-*/
cp bin/* /usr/local/bin/
cp lib/* /usr/local/lib/
chmod 777 /usr/local/bin/relation-graph
```
4. If prompted by MacOS, [allow permissions](https://support.apple.com/en-us/HT202491).

## Build
Install `sbt` (Scala Build Tool) on your system. For Mac OS X, it is easily done using [Homebrew](http://brew.sh):  `brew install sbt`. `sbt` requires a working Java installation, but you do not need to otherwise install Scala.

After `sbt` is installed, run `sbt stage` to create the executable. The executable is created in your `cli/target/universal/stage/bin` directory.
