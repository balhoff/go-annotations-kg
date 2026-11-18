---
template: overrides/kg.html
shortname: go-annotations
title: Gene Ontology Annotations
description: Gene Ontology annotations of UniProtKB proteins, RNAcentral RNAs and Complex Portal complexes, with evidence and provenance, from each GO release
homepage: https://geneontology.org/docs/go-annotations/
frink-options:
  kgf:
    semantics:
      prefixes:
        goann: "https://w3id.org/go-annotation-kg/vocab/"
        GO: "http://purl.obolibrary.org/obo/GO_"
        RO: "http://purl.obolibrary.org/obo/RO_"
        BFO: "http://purl.obolibrary.org/obo/BFO_"
        ECO: "http://purl.obolibrary.org/obo/ECO_"
        NCBITaxon: "http://purl.obolibrary.org/obo/NCBITaxon_"
        GO_REF: "http://purl.obolibrary.org/obo/go/references/"
        UniProtKB: "http://purl.uniprot.org/uniprot/"
        uniprot.isoform: "http://purl.uniprot.org/isoforms/"
        RNAcentral: "http://identifiers.org/rnacentral/"
        ComplexPortal: "https://www.ebi.ac.uk/complexportal/complex/"
        PMID: "http://www.ncbi.nlm.nih.gov/pubmed/"
      authoritative_namespaces:
        - goann
      roles:
        label:
          - "http://www.w3.org/2000/01/rdf-schema#label"
        synonym:
          - "http://www.w3.org/2004/02/skos/core#altLabel"
          - "https://w3id.org/go-annotation-kg/vocab/fullName"
contact:
  email: TODO
  github: TODO
  label: TODO
license: "https://creativecommons.org/licenses/by/4.0/"
---
The [Gene Ontology](https://geneontology.org) annotations of each GO release, converted from GO's per-species GPAD 2.0 and GPI 2.0 files for UniProtKB proteins, RNAcentral RNAs and Complex Portal complexes (about 15.4 million annotations of 1.9 million gene products in 172 species, as of release 2026-08-05).

Each gene product → GO term relation is a plain triple using its Relations Ontology predicate (for example `RO:0002327` *enables*), so `?protein RO:0002331 GO:0006915` finds the proteins involved in apoptosis. Every GPAD line is an RDF 1.2 reifier of that triple (`rdf:reifies`), typed `goann:Annotation` and carrying the line's ECO evidence (`RO:0002558`), GO evidence code, reference, with/from, date, assigning group, annotation extensions and GO-CAM model. Annotations with GO's NOT qualifier are typed `goann:NegatedAnnotation` and point at their triple with `goann:negates`; they never assert it, so a plain triple is always a positive annotation. Gene products carry their symbol, name, synonyms, type, taxon (`RO:0002162`) and database cross-references from the GPI.

GO terms, ECO and RO are not copied: join to ubergraph for their labels and hierarchies.
