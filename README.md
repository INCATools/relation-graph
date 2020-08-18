# relation-graph

Materialize OWL existential relations

## Run

```bash
relation-graph --ontology-file uberon.owl --non-redundant-output-file nonredundant.ttl --redundant-output-file redundant.ttl --mode rdf --property 'http://purl.obolibrary.org/obo/BFO_0000050' --property 'http://purl.obolibrary.org/obo/BFO_0000051'
```

You can leave off the `property` arguments; in that case all OWL object properties are used. The default mode is `rdf`; alternatively, the `owl` mode will
output RDF, but as a valid OWL structure.
