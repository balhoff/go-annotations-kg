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
        goann: "https://w3id.org/go-annotations-kg/vocab/"
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
        lego: "http://geneontology.org/lego/"
        pav: "http://purl.org/pav/"
        prov: "http://www.w3.org/ns/prov#"
        oboInOwl: "http://www.geneontology.org/formats/oboInOwl#"
        up: "http://purl.uniprot.org/core/"
        dcat: "http://www.w3.org/ns/dcat#"
      authoritative_namespaces:
        - goann
      roles:
        label:
          - "http://www.w3.org/2000/01/rdf-schema#label"
        synonym:
          - "http://www.geneontology.org/formats/oboInOwl#hasExactSynonym"
          - "http://www.geneontology.org/formats/oboInOwl#hasRelatedSynonym"
contact:
  email: TODO
  github: TODO
  label: TODO
license: "https://creativecommons.org/licenses/by/4.0/"
---
The [Gene Ontology](https://geneontology.org) annotations of each GO release, converted from GO's per-species GPAD 2.0 and GPI 2.0 files for UniProtKB proteins, RNAcentral RNAs and Complex Portal complexes (about 15.4 million annotations of 1.9 million gene products in 172 species, as of release 2026-08-05).

Each gene product → GO term relation is a plain triple using its Relations Ontology predicate (for example `RO:0002327` *enables*), so `?protein RO:0002331 GO:0006915` finds the proteins involved in apoptosis. Every GPAD line is an RDF 1.2 reifier of that triple (`rdf:reifies`), carrying the line's ECO evidence (`RO:0002558`), reference (`dcterms:source`), with/from (`lego:evidence-with`), date, assigning group (`pav:providedBy`), annotation extensions and GO-CAM model (`prov:wasDerivedFrom`). Each ECO class used carries its GO evidence code (`ECO:9000002`, ECO's own "has GO evidence code"). Positive lines are typed `goann:Annotation`. Lines with GO's NOT qualifier are typed `goann:NegatedAnnotation` and never assert their triple, so a plain triple is always a positive annotation; a query for the evidence behind a triple should ask for `goann:Annotation`. Gene products carry their symbol (`rdfs:label`), name and synonyms (oboInOwl synonyms), type, taxon (`RO:0002162`), Swiss-Prot status (`up:reviewed`) and database cross-references (`rdfs:seeAlso`) from the GPI.

GO terms, ECO and RO are not copied: join to ubergraph for their labels and hierarchies.
