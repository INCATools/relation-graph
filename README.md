# relation-graph

Materialize OWL existential relations

## Run

```bash
relation-graph --ontology-file uberon.owl --non-redundant-output-file nonredundant.ttl --redundant-output-file redundant.ttl --mode rdf --property 'http://purl.obolibrary.org/obo/BFO_0000050' --property 'http://purl.obolibrary.org/obo/BFO_0000051' --properties-file more_properties.txt
```

You can leave off the `property` and `properties-file` arguments; in that case all OWL object properties are used. The default mode is `rdf`; 
alternatively, the `owl` mode will output RDF, but as a valid OWL structure. The `properties-file` should look like this:

```
http://purl.obolibrary.org/obo/BFO_0000050
http://purl.obolibrary.org/obo/BFO_0000051
```
**Note:** Before running, you may need to increase the amount of memory available for Java. On UNIX style systems, this is done by setting the `JAVA_OPTS` environment variable. For example, to set the amount of memory to `16G`, you would use this command: `export JAVA_OPTS=-Xmx16G`.

## Build
Install `sbt` (Scala Build Tool) on your system. For Mac OS X, it is easily done using [Homebrew](http://brew.sh):  `brew install sbt`. `sbt` requires a working Java installation, but you do not need to otherwise install Scala.

After `sbt` is installed, run `sbt stage` to create the executable. The executable is created in your `target/universal/stage/bin` directory.
