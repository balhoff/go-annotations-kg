# Graph model

How a GO release's GPAD and GPI files become RDF 1.2. The vocabulary is defined in
[`../vocab/goann.ttl`](../vocab/goann.ttl), and [`queries/`](queries/) holds worked
SPARQL 1.2 queries.

## Goals

1. **The gene product → GO term relation is a plain triple.** "Which proteins are
   involved in apoptosis?" should be one triple pattern, and should need no knowledge
   of the annotation layer.
2. **A plain triple is never wrong.** Every asserted gene product → GO term triple is
   backed by at least one positive annotation. In prokn, by contrast, `NOT`
   annotations are also asserted as positive triples. (Its schema notes call this
   out; checked 2026-10-16.)
3. **Lossless.** Every GPAD column and annotation property can be recovered from the
   graph, so this can be the canonical source of GO annotations in the OKN.
4. **Joins with the rest of the OKN.** Identifiers are written the way Babel, ROBOKOP
   and prokn write them.

## Sources

Each GO release publishes one GPAD 2.0 and one GPI 2.0 file per species, under
`https://release.geneontology.org/{date}/annotations/`. Only the `*-uniprot` pairs are
converted: 172 species in release 2026-08-05. The 21 `*-mod.gpad.gz` files repeat the
same annotations keyed to MOD gene identifiers. MOD gene identifiers still reach the
graph, as cross-references from the GPI (`goann:xref`).

## The shape

Each GPAD line becomes an **annotation node** that reifies the triple it supports.
The triple itself is asserted once, however many lines support it. In Turtle 1.2,
for two real lines from `HUMAN-uniprot.gpad.gz`:

```turtle
PREFIX GO: <http://purl.obolibrary.org/obo/GO_>
PREFIX RO: <http://purl.obolibrary.org/obo/RO_>
PREFIX ECO: <http://purl.obolibrary.org/obo/ECO_>
PREFIX GO_REF: <http://purl.obolibrary.org/obo/go/references/>
PREFIX UniProtKB: <http://purl.uniprot.org/uniprot/>
PREFIX dcterms: <http://purl.org/dc/terms/>
PREFIX goann: <https://w3id.org/go-annotation-kg/vocab/>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>

UniProtKB:A0A024RBG1 RO:0002327 GO:0008486          # enables diphosphoinositol-polyphosphate diphosphatase activity
  {| a goann:Annotation ;                            # GPAD line: ... GO_REF:0000003  ECO:0000501  EC:3.6.1.52 ...
     RO:0002558 ECO:0000501 ;                        #   has evidence
     goann:evidenceCode "IEA" ;
     dcterms:source GO_REF:0000003 ;
     goann:withFrom <http://www.enzyme-database.org/query.php?ec=3.6.1.52> ;
     dcterms:date "2026-07-27"^^xsd:date ;
     goann:assignedBy "UniProt" ;
     dcterms:identifier "GOA:7017011106" |}
  {| a goann:Annotation ;                            # GPAD line: ... GO_REF:0000024  ECO:0000250  UniProtKB:Q9NZJ9 ...
     RO:0002558 ECO:0000250 ;
     goann:evidenceCode "ISS" ;
     dcterms:source GO_REF:0000024 ;
     goann:withFrom UniProtKB:Q9NZJ9 ;
     dcterms:date "2024-07-03"^^xsd:date ;
     goann:assignedBy "UniProt" ;
     dcterms:contributor <https://orcid.org/0000-0002-2979-8613> ;
     dcterms:identifier "GOA:2114745301" |} .
```

`{| … |}` is Turtle 1.2's annotation syntax. Each block is a blank node `_:r` with
`_:r rdf:reifies <<( UniProtKB:A0A024RBG1 RO:0002327 GO:0008486 )>>` plus the
properties inside it. That is exactly what the converter writes, in N-Triples 1.2.

### NOT annotations

A `NOT` line says its triple is false. It must not be asserted. RDF 1.2 can point at
a triple without asserting it, so a `NOT` line becomes a node that points at the
triple term with **`goann:negates`**, not `rdf:reifies`:

```turtle
[] a goann:NegatedAnnotation ;
   goann:negates <<( UniProtKB:A0A087X1C5 RO:0002327 GO:0070330 )>> ;
   RO:0002558 ECO:0000314 ;
   goann:evidenceCode "IDA" ;
   dcterms:source <http://www.ncbi.nlm.nih.gov/pubmed/18838503> ;
   dcterms:date "2015-01-23"^^xsd:date ;
   goann:assignedBy "UniProt" .
```

A separate predicate, rather than `rdf:reifies` plus a flag, means:

- `?a rdf:reifies <<( ?gp ?rel ?term )>>` returns positive evidence only, so a query
  that forgets negation is still right;
- the class partition counts the two kinds apart (`goann:Annotation`,
  `goann:NegatedAnnotation`).

In release 2026-08-05, 1,214 (gene product, relation, term) triples have both
positive and `NOT` annotations. Such a triple is asserted (some line supports it),
and its `goann:negates` node shows the conflict.

### Annotation extensions

Column 11 narrows the GO term: `BFO:0000066(GO:0005634),RO:0002233(UniProtKB:Q00362)`
means the gene product enables *this activity, occurring in the nucleus, with that
input*. Each `|`-separated conjunction becomes an anonymous class. Its
`rdfs:subClassOf` values are OWL existential restrictions:

```turtle
UniProtKB:P12345 RO:0002327 GO:0004674
  {| …
     goann:extension [
       rdfs:subClassOf [ owl:onProperty BFO:0000066 ; owl:someValuesFrom GO:0005634 ] ,
                       [ owl:onProperty RO:0002233 ; owl:someValuesFrom UniProtKB:Q00362 ] ] |} .
```

The annotated class is the GO term intersected with that class. Several
`goann:extension` values are independent of each other, as GO defines `|`: each
could have been its own GPAD line.

The obvious alternative is the relation as a predicate (`[] BFO:0000066 GO:0005634`).
It was rejected because extensions reuse the annotation relations. `BFO:0000050`
(part of) is both the relation of 668k complex annotations and a common extension
relation. As predicates, `?x BFO:0000050 GO:0030426` would return extension nodes
mixed in with gene products. With restrictions, this invariant holds:

> **The 13 gene product → GO term relations occur as predicates only in asserted,
> positively annotated gene product → GO term triples.**

Malformed extension elements are dropped and counted. In 2026-08-05 there are 34, all
`has_regulation_target(...)`, which uses a label where an RO id belongs. Dropping one
element of a conjunction leaves a weaker statement that is still true.

### Gene products

Each annotated gene product is described from the GPI, and only annotated ones: the
uniprot GPIs list 5.2M entities, of which 1.9M are annotated.

```turtle
UniProtKB:A0A024RBG1 a PR:000000001 ;                   # protein (GPI column 5)
  rdfs:label "NUDT4B" ;                                  # symbol
  goann:fullName "Diphosphoinositol polyphosphate phosphohydrolase NUDT4B" ;
  RO:0002162 NCBITaxon:9606 ;                            # in taxon
  goann:dbSubset "Swiss-Prot" ;
  goann:xref HGNC:18012 , ENSEMBL:ENSG00000173598 , ENSEMBL:ENSG00000177144 ,
             <http://identifiers.org/refseq/NP_001342336.1> , <http://identifiers.org/insdc/AC245389> .
```

When the GPI lacks an annotated object (107k in 2026-08-05, nearly all RNAcentral),
the converter writes what the identifier itself gives. An RNAcentral id ends in its
taxon (`URS000000768F_575584`). An isoform or chain id names its parent accession,
which gives `goann:canonicalObject` and the parent's taxon.

## Column by column

### GPAD 2.0

| Column | Becomes |
|---|---|
| 1 DB_Object_ID | subject of the triple (term) |
| 2 Negation | `goann:Annotation` + `rdf:reifies` + asserted triple, or `goann:NegatedAnnotation` + `goann:negates` |
| 3 Relation | predicate of the triple (term) |
| 4 Ontology_Class_ID | object of the triple (term) |
| 5 Reference | `dcterms:source` |
| 6 Evidence_Type | `RO:0002558` (has evidence) → ECO class |
| 7 With_Or_From | `goann:withFrom` for every id; `goann:withFromGroup [ rdfs:member … ]` for each `,` group |
| 8 Interacting_Taxon_ID | `goann:interactingTaxon` |
| 9 Annotation_Date | `dcterms:date` (`xsd:date` or `xsd:dateTime`) |
| 10 Assigned_By | `goann:assignedBy` (literal: GO's group abbreviation) |
| 11 Annotation_Extensions | `goann:extension`, as above |
| 12 `id=` | `dcterms:identifier` (literal) |
| 12 `comment=go_evidence:XXX` | `goann:evidenceCode "XXX"` |
| 12 `comment=` other | `rdfs:comment` |
| 12 `contributor-id=` | `dcterms:contributor` (ORCID IRI) |
| 12 `reviewer-id=` | `goann:reviewer` |
| 12 `creation-date=` / `modification-date=` / `reviewed-date=` | `dcterms:created` / `dcterms:modified` / `goann:reviewed` |
| 12 `noctua-model-id=` / `model-state=` | `goann:gocamModel` (GO-CAM model IRI) / `goann:modelState` |
| header | a `goann:SourceFile` node: `goann:formatVersion`, `dcterms:creator`, `dcterms:created`, `goann:goVersion` |

### GPI 2.0

| Column | Becomes |
|---|---|
| 2 DB_Object_Symbol | `rdfs:label` (the name when there is no symbol, as for RNAcentral) |
| 3 DB_Object_Name | `goann:fullName` |
| 4 DB_Object_Synonyms | `skos:altLabel`, without the symbol and name themselves |
| 5 DB_Object_Type | `rdf:type` (PR, SO or GO class) |
| 6 DB_Object_Taxon | `RO:0002162` (in taxon) |
| 7 Encoded_By | `RO:0002204` (gene product of) |
| 8 Canonical_Object_ID | `goann:canonicalObject`, when it is not the entity itself |
| 9 Protein_Containing_Complex_Members | `RO:0002180` (has component) |
| 10 DB_Xrefs | `goann:xref` |
| 11 `db_subset=` | `goann:dbSubset` |

## Decisions, and why

**RO relations, not Biolink predicates.** GPAD 2.0 already names its relations with RO
ids, and RO's property hierarchy is in ubergraph. A query for *acts upstream of or
within* (`RO:0002264`) can take in its subproperties, *involved in* among them,
through `rdfs:subPropertyOf*` there. Biolink has an exact mapping for all 13
relations (`biolink:enables`, `biolink:actively_involved_in`, …), so the
Biolink view is a 13-row mapping, not a remodelling.

**One node per GPAD line, as a blank node.** GOA's `id=` property is not an identity
to build on. In 2026-08-05, 340 ids occur twice, for different annotations, in
species files UniProt generated on different days. IEA ids are also re-minted on
every UniProt run. An IRI built from them would claim an identity the source does not
have. The node's label is the species code and file line (`HUMANa5`, which Jena
writes as `_:BHUMANa5`), so the same input always gives byte-identical output. In a
KGF bundle it is served under the bundle's stable blank-node wire name
(`urn:fdc:frink-okn.github.io:…:bnode:…`). The GOA id is still kept, as
`dcterms:identifier`, the one handle back to GOA.

**Evidence twice.** The ECO class (`RO:0002558`) is precise, and its hierarchy is in
ubergraph. The three-letter GO code (`goann:evidenceCode`) is what nearly every user
filters on ("no IEA"), and it needs no join to get.

**With/from flattened, groups kept.** `goann:withFrom` lists every identifier, so
"which annotations cite Q9NZJ9" is one pattern. The 8,002 lines with `,`-groups
(mostly IGI double mutants) also get a group node, so nothing is lost.

**Identifiers.** CURIEs are expanded with [`../prefixes.tsv`](../prefixes.tsv). Its
policy:
- Use the Biolink prefix map wherever Biolink has the prefix: the same commit
  `babel-rdf` uses, so identifiers join through Babel.
- UniProtKB is `http://purl.uniprot.org/uniprot/`, as in prokn and UniProt's own
  SPARQL endpoint.
- Isoforms are `http://purl.uniprot.org/isoforms/`.
- Prefixes repeated inside the local part (`MGI:MGI:97486`) are removed, as Babel
  writes them.
- GO's own IRIs are used where GO owns the identifiers: `GO_REF` and GO-CAM models.
- Everything else gets an identifiers.org IRI.
- Sequence-record xrefs with no gene-level meaning (Gramene `GR`, `WGS`) are dropped
  on purpose.

An identifier with an unlisted prefix is dropped and counted in the run's report, so
a new prefix in a later release shows up there.

**No ontology content.** GO term labels and hierarchy, ECO and RO are in ubergraph,
already in the OKN. Copying them would pin a second, possibly different, version. The
bundle does label the relations it uses, in `vocab/goann.ttl`, so the graph reads
on its own.

## Size (release 2026-08-05)

From `make convert-all` and `make report` over all 172 `*-uniprot` pairs. Run with
`-j8` on a 12-core laptop, the conversion takes 83 seconds.

| | |
|---|---:|
| GPAD lines | 15,454,965 |
| positive annotations (`goann:Annotation`) | 15,445,702 |
| NOT annotations (`goann:NegatedAnnotation`) | 9,263 |
| asserted gene product → GO term triples | 11,197,555 |
| triples with only NOT annotations, so never asserted | 8,735 |
| gene products described from the GPI | 1,808,506 |
| annotated objects missing from the GPI | 107,523 |
| extension conjunctions | 170,961 |
| triples written | 192,398,252 |
| N-Triples 1.2 output, gzipped | 1.2 GB |

The asserted-triple count equals the number of distinct positive (object, relation,
term) rows in the source, as checked in DuckDB. No GPAD line was dropped. The report
counts what was dropped below the line level:

- 34 extension elements (`has_regulation_target(...)`), which empties 31
  conjunctions;
- 3 with/from values with no prefix;
- 10,060 GPI xrefs with no prefix;
- 1.34M GPI xrefs dropped on purpose (`GR`, `WGS`, `MGII`).

A KGF bundle of HUMAN alone has 9.84M triples and takes 151 MiB. It builds with
`kgf` 0.6.0 and `hdtc` 1.3.2 in 22 seconds. In it:

- the 1,497 NOT propositions sit in `data.hdt.props.hdt`, the side file for
  unasserted triple terms;
- `?a rdf:reifies <<( UniProtKB:P04637 ?p ?o )>>` counts 1,049, which is TP53's
  positive GPAD lines;
- `goann:negates <<( ?s RO:0002327 ?o )>>` counts 618.

All of these match the source rows. Scaled up, the whole release should make a
bundle of about 3 GB.
