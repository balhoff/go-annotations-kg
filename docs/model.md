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
5. **Existing vocabulary first.** A property GO, OBO or the data's source already
   uses is reused; `goann:` holds only what nothing existing covers.

## Sources

Each GO release publishes one GPAD 2.0 and one GPI 2.0 file per species, under
`https://release.geneontology.org/{date}/annotations/`. Only the `*-uniprot` pairs are
converted: 172 species in release 2026-08-05. The 21 `*-mod.gpad.gz` files repeat the
same annotations keyed to MOD gene identifiers. MOD gene identifiers still reach the
graph, as GPI cross-references (`rdfs:seeAlso`).

Metadata files are read alongside:

- GO's `groups.ttl` and `db-xrefs.json`, from the same release, for the groups that
  assign annotations;
- GO's `release-archive-doi.json`, which names the release's Zenodo archive DOI;
- ECO's OWL release, `eco.owl`, for the GO evidence code of each ECO class. ECO is not
  part of a GO release, so its `owl:versionIRI` is recorded in the graph. OBO format is
  not read: it is not a well-defined format, and ECO publishes an official OWL file.

## Versions and provenance

**The version label is the GO release date** (`2026-08-05`), for the KGF bundle and
in the graph. If a conversion turns out wrong, the converter is fixed and that
release rebuilt under the same label: `make bundle REPLACE=1` builds the replacement
and moves the old bundle aside to `.replaced/`. The Makefile never deletes a bundle.
The KGF manifest's `previous_version` names the newest bundle of an earlier release.

**In the graph**, a few dozen triples per build say where the data came from:

```turtle
<https://doi.org/10.5281/zenodo.21844811> a dcat:Dataset ;       # the GO release, by its archive DOI
    rdfs:label "Gene Ontology release 2026-08-05" ;
    pav:version "2026-08-05" ;
    dcterms:hasPart <https://release.geneontology.org/2026-08-05/annotations/gpad/HUMAN-uniprot.gpad.gz> ,
                    <https://release.geneontology.org/2026-08-05/metadata/groups.ttl> , … .

<https://purl.org/okn/frink/kg/go-annotations>                    # this KG
    pav:version "2026-08-05" ;
    prov:wasDerivedFrom <https://doi.org/10.5281/zenodo.21844811> ,
                        <http://purl.obolibrary.org/obo/eco/releases/2026-07-10/eco.owl> ;
    pav:createdWith <https://w3id.org/go-annotation-kg/> .

<https://w3id.org/go-annotation-kg/> rdfs:label "go-annotation-kg" ;
    pav:version "621b65a" .                                        # git describe; "-dirty" if uncommitted
```

Each GPAD, GPI and metadata file is a `goann:SourceFile` with its header details. The
GO version a GPAD or GPI file was checked against is recorded per file
(`goann:goVersion`), because GOA generates species files on different days, against
different GO versions. When GO gives no DOI file, the release is named by its snapshot
URL, `https://release.geneontology.org/2026-08-05/`.

**In the KGF manifest**, alongside: the content digest, KGF's identity for a version's
bytes; the SHA-256 of each input file; the `kgf` and `hdtc` versions; the source URL;
and `previous_version`. Facts about the data's origin are in the graph, so they
survive exports and copies; facts about the build are in the manifest.

## The shape

Each GPAD line becomes an **annotation node** that reifies the triple it is about:
the triple it supports, or for a `NOT` line the triple it denies. The triple itself is
asserted once, however many positive lines support it. In Turtle 1.2, for two real
lines from `HUMAN-uniprot.gpad.gz`:

```turtle
PREFIX GO: <http://purl.obolibrary.org/obo/GO_>
PREFIX RO: <http://purl.obolibrary.org/obo/RO_>
PREFIX ECO: <http://purl.obolibrary.org/obo/ECO_>
PREFIX GO_REF: <http://purl.obolibrary.org/obo/go/references/>
PREFIX UniProtKB: <http://purl.uniprot.org/uniprot/>
PREFIX dcterms: <http://purl.org/dc/terms/>
PREFIX goann: <https://w3id.org/go-annotation-kg/vocab/>
PREFIX lego: <http://geneontology.org/lego/>
PREFIX pav: <http://purl.org/pav/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>

UniProtKB:A0A024RBG1 RO:0002327 GO:0008486          # enables diphosphoinositol-polyphosphate diphosphatase activity
  {| a goann:Annotation ;                            # GPAD line: ... GO_REF:0000003  ECO:0000501  EC:3.6.1.52 ...
     RO:0002558 ECO:0000501 ;                        #   has evidence
     dcterms:source GO_REF:0000003 ;
     lego:evidence-with <http://www.enzyme-database.org/query.php?ec=3.6.1.52> ;
     dcterms:date "2026-07-27"^^xsd:date ;
     pav:providedBy <https://www.uniprot.org> ;
     dcterms:identifier "GOA:7017011106" |}
  {| a goann:Annotation ;                            # GPAD line: ... GO_REF:0000024  ECO:0000250  UniProtKB:Q9NZJ9 ...
     RO:0002558 ECO:0000250 ;
     dcterms:source GO_REF:0000024 ;
     lego:evidence-with UniProtKB:Q9NZJ9 ;
     dcterms:date "2024-07-03"^^xsd:date ;
     pav:providedBy <https://www.uniprot.org> ;
     dcterms:contributor <https://orcid.org/0000-0002-2979-8613> ;
     dcterms:identifier "GOA:2114745301" |} .

ECO:0000501 ECO:9000002 "IEA" .                      # ECO's "has GO evidence code"
ECO:0000250 ECO:9000002 "ISS" .
<https://www.uniprot.org> rdfs:label "UniProt" .     # the group, labelled with its GPAD abbreviation
```

`{| … |}` is Turtle 1.2's annotation syntax. Each block is a blank node `_:r` with
`_:r rdf:reifies <<( UniProtKB:A0A024RBG1 RO:0002327 GO:0008486 )>>` plus the
properties inside it. That is exactly what the converter writes, in N-Triples 1.2.

The GO evidence code is not repeated on each annotation. It is ECO's to give, on the
ECO class (`ECO:9000002`), so "no IEA" is `?a RO:0002558 ?eco . ?eco ECO:9000002 ?code`
with `FILTER(?code != "IEA")`. The ECO triples for every class a file uses are written
with it.

### NOT annotations

A `NOT` line says its triple is false, so the line must not assert it. Like every
line, it reifies the triple. Its type, **`goann:NegatedAnnotation`**, says it denies
the proposition rather than supports it. Turtle 1.2's reified-triple syntax writes
`rdf:reifies` without asserting the triple:

```turtle
<< UniProtKB:A0A087X1C5 RO:0002327 GO:0070330 >>    # reified, not asserted
   a goann:NegatedAnnotation ;
   RO:0002558 ECO:0000314 ;
   dcterms:source <http://www.ncbi.nlm.nih.gov/pubmed/18838503> ;
   dcterms:date "2015-01-23"^^xsd:date ;
   pav:providedBy <https://www.uniprot.org> .
```

This is how RDF 1.2 intends disputed and false claims to be written:

- RDF 1.2 Concepts §1.5 calls the meaning of `rdf:reifies` "deliberately generic".
  - A reifier may denote "a variety of things that are related to the triple
    term's proposition".
  - A reified proposition "does not have to hold", even when it contradicts
    another statement.
  - A curator's statement that a proposition is false is one of those things.
- The RDF 1.2 Primer suggests unasserted triple terms for "disputed claims". It also
  says triple terms "should be used with the special reifies predicate".
- Turtle 1.2's and SPARQL 1.2's shorthands, `<< s p o ~ r >>` and `{| … |}`, both
  expand to `rdf:reifies`. One pattern therefore reaches every annotation of a
  triple, positive or not.

A first draft used a separate predicate, `goann:negates`, so that `rdf:reifies`
would mean positive evidence only. It was dropped (2026-11-18): the type does the
same job without leaving the standard idiom.

The flag is the type, not a boolean:

- every reifier has exactly one of the two types, which are declared disjoint;
- the class partition counts the two kinds apart;
- with a boolean on `NOT` lines only, "positive" would rest on a value being
  absent.

**A query for supporting evidence must ask for `?a a goann:Annotation`**, as every
query in [`queries/`](queries/) does. It matters for triples that have both kinds of
line. In release 2026-08-05, 1,214 triples have both (1,324 `NOT` lines). Each is
asserted, because a positive line states it, so `?gp ?rel ?term {| … |}` on it
returns both kinds. The other 7,521 `NOT`-annotated triples are never asserted, so
that pattern cannot reach them; `<< ?gp ?rel ?term ~ ?a >>` can. A query over `NOT`
annotations should start from the type, which is selective, and join on the whole
triple term (query 05).

### Annotation extensions

Column 11 narrows the GO term: `BFO:0000066(GO:0005634),RO:0002233(UniProtKB:Q00362)`
means the gene product enables *this activity, occurring in the nucleus, with that
input*. Each `|`-separated conjunction becomes an anonymous class. Its
`rdfs:subClassOf` values are OWL existential restrictions:

```turtle
UniProtKB:P12345 RO:0002327 GO:0004674               # an illustration, not a real line
  {| …
     goann:extension [
       rdfs:subClassOf [ owl:onProperty BFO:0000066 ; owl:someValuesFrom GO:0005634 ] ,
                       [ owl:onProperty RO:0002233 ; owl:someValuesFrom UniProtKB:Q00362 ] ] |} .
```

The annotated class is the GO term intersected with that class. Several
`goann:extension` values are independent of each other, as GO defines `|`: each
could have been its own GPAD line.

The obvious alternative is the relation as a predicate, `[] BFO:0000066 GO:0005634`.
That is ubergraph's convention for existential edges, it is about half the size, and
queries are one hop shorter. It was rejected because extensions reuse one annotation
relation, `BFO:0000050` (*part of*):
- *part of* is the relation of 668k complex annotations;
- it is also 72,292 of the 235,747 extension elements (31%).

As predicates, `?x BFO:0000050 ?cc` would return extension nodes mixed in with gene
products, and *part of*'s counts would merge. With restrictions, this invariant
holds:

> **The 13 gene product → GO term relations occur as predicates only in asserted,
> positively annotated gene product → GO term triples.**

Malformed extension elements are dropped and counted. Dropping one element of a
conjunction leaves a weaker statement that is still true. An element's relation must
be a numeric OBO id: labels and composed names would otherwise expand to IRIs that
name nothing. In 2026-08-05 there are 102 malformed elements:

| Element | Count |
|---|---:|
| `RO:0002211_transport_of(...)` | 67 |
| `has_regulation_target(...)` | 25 |
| `occurs_at(...)` | 9 |
| `RO:0002211_o_RO:0002565(...)`, a property chain | 1 |

Dropping them empties 40 conjunctions.

#### Interacting taxon

Column 8, the taxon of another organism involved, narrows the GO term in the same
way, so it is written as an extension. It becomes a `has input` (`RO:0002233`)
restriction to the NCBITaxon class in every conjunction of the line, or a conjunction
of its own when the line has none. Two precedents:

- **GO-CAM writes it this way.** Minerva's GPAD export reads a `has input` edge to an
  NCBITaxon class back as column 8.
- **GO's own logical definitions agree.** Most of the 2,241 lines are host
  defense terms; GO defines *defense response to bacterium* (GO:0042742) as
  *defense response* and `has_primary_input` some `NCBITaxon:2`.
  `has_primary_input` (RO:0004009) is a subproperty of `has input`. So
  GO:0050830 with taxon 1280 means *defense response to Staphylococcus aureus*.

```turtle
# GPAD: UniProtKB:O15263  RO:0002331  GO:0050830  PMID:15625724  ECO:0000314  NCBITaxon:taxon:1280
UniProtKB:O15263 RO:0002331 GO:0050830               # DEFB4A involved in defense response to Gram-positive bacterium
  {| a goann:Annotation ;
     goann:extension [ rdfs:subClassOf [ owl:onProperty RO:0002233 ; owl:someValuesFrom NCBITaxon:1280 ] ] ;
     … |} .
```

Several interacting taxa, like several conjunctions, are independent. Each gives its
own copy of each conjunction. A column-8 taxon is now indistinguishable from an
explicit `has input`(NCBITaxon) extension, which GO treats as the same thing. No
extension in 2026-08-05 targets a taxon.

GOA currently writes every interacting taxon as `NCBITaxon:taxon:N`, a bug in its
pipeline that is due to be fixed. The prefix table's `strip=taxon:` rule undoes it.

### Gene products

Each annotated gene product is described from the GPI, and only annotated ones: the
uniprot GPIs list 5.2M entities, of which 1.9M are annotated.

```turtle
UniProtKB:A0A024RBG1 a PR:000000001 ;                   # protein (GPI column 5)
  rdfs:label "NUDT4B" ;                                  # symbol
  oboInOwl:hasExactSynonym "Diphosphoinositol polyphosphate phosphohydrolase NUDT4B" ;
  RO:0002162 NCBITaxon:9606 ;                            # in taxon
  up:reviewed true ;                                     # Swiss-Prot
  rdfs:seeAlso HGNC:18012 , ENSEMBL:ENSG00000173598 , ENSEMBL:ENSG00000177144 ,
               <http://identifiers.org/refseq/NP_001342336.1> , <http://identifiers.org/insdc/AC245389> .
```

When the GPI lacks an annotated object (107k in 2026-08-05, nearly all RNAcentral),
the converter writes what the identifier itself gives. An RNAcentral id ends in its
taxon (`URS000000768F_575584`). An isoform or chain id names its parent accession,
which gives `goann:canonicalObject` and the parent's taxon.

### Groups

`pav:providedBy` gives the IRI GO's `groups.ttl` assigns the group, as GO-CAM does.
Each group used is written with its GPAD abbreviation as `rdfs:label`, and its longer
name, where different, as `oboInOwl:hasExactSynonym`. For example, `http://geneontology.org`
has the label "GO_Central" and the synonym "GO Central".

Some assigners are missing from `groups.ttl`:

- the four Ensembl divisions, CollecTF and PHI-base use their homepage from
  `db-xrefs.json`;
- GDB, MENGO, Others and SYSCILIA_CCNET have neither, so `pav:providedBy` is their
  abbreviation as a literal: 8,136 lines in 2026-08-05.

The report counts these under `unresolved.assigned-by`. They want `groups.yaml`
entries upstream.

### Labels for external terms

Every external term the graph uses gets an `rdfs:label`, so the graph reads on its own.
That covers:

- GO terms and the relations;
- ECO classes and taxa;
- the ontologies of extension targets (PR, CHEBI, EMAPA, UBERON, CL, WBbt, SO, FBbt,
  WBls, DDANAT, ZFA, PO, FBdv);
- GO_REFs.

Only labels are included, and only for terms the graph uses. Gene products are
labelled from the GPI, and groups from GO's `groups.ttl`.

The labels step runs once per release, after the species conversions:

1. Each conversion lists the OBO-namespace IRIs its output uses, including those that
   occur only inside a `NOT` line's triple term.
2. The step streams each needed ontology's OWL release once. It keeps only
   `rdfs:label` triples whose subject is a used term in that ontology's own
   namespace. An ontology's import of another's term (GO's own `go-plus.owl` imports
   CHEBI and CL terms, for example) can carry a stale label, so it is never used.
3. GO_REF titles come from the release's `gorefs.yaml`.
4. Each ontology read is recorded by its `owl:versionIRI` as a `goann:SourceFile`, with
   `pav:version`, and as a source the dataset `prov:wasDerivedFrom`.

[`../ontologies.tsv`](../ontologies.tsv) names the source of each namespace:

- GO and GO's legacy relations (GOREL) come from the release being converted;
- ECO is the release's downloaded `eco.owl`;
- RO labels the BFO relations GO uses;
- every other namespace uses its OBO purl.

In release 2026-08-05 the step labels 37,913 of the 37,975 ontology terms the graph
uses, and writes 37,967 triples, labels and provenance included. Streaming the
ontologies took about 12 minutes on a laptop; CHEBI alone took 9½.

| Namespace | Used | Labelled | Source version |
|---|---:|---:|---|
| GO | 28,293 | 28,276 | `go/releases/2026-07-26` (the release's `go.owl`) |
| PR | 4,201 | 4,183 | `pr/73.1` |
| CHEBI | 1,577 | 1,572 | `chebi/257` |
| EMAPA | 1,456 | 1,456 | `emapa/releases/2026-10-02` |
| UBERON | 748 | 743 | `uberon/releases/2026-11-05` |
| CL | 708 | 702 | `cl/releases/2026-11-13` |
| NCBITaxon | 360 | 351 | `ncbitaxon/2026-11-09` |
| WBbt | 213 | 212 | `wbbt/releases/2025-08-19` |
| ECO | 152 | 152 | `eco/releases/2026-07-10` |
| SO | 94 | 94 | `so/2026-08-07` |
| RO, BFO, GOREL | 50 | 50 | `ro/releases/2026-09-04`; the release's `gorel.owl` |
| GO_REF | 36 | 36 | the release's `gorefs.yaml` |
| FBbt, WBls, DDANAT, ZFA, PO, FBdv | 87 | 86 | their OBO releases |

The 62 unlabelled terms are listed in `labels.unlabelled.tsv`:

- **Retired ids.** Where the ontology records a merge, the list names the current
  term.
  - 17 GO ids, all extension targets, which GOA's checks evidently miss. 16 of them
    are merged into a current term: GO:0016021 (*integral component of membrane*)
    into GO:0016020, for example.
  - 9 NCBITaxon ids, all interacting taxa (106 lines). NCBITaxon:10633, for one, is
    merged into NCBITaxon:1891767.
  - A few CHEBI, CL, UBERON, WBbt and WBls extension targets.
- **PR ids that PR does not define.** 18 UniProt-style ids such as `PR:P04230`.

OMIM ids reach the graph only as GPI cross-references (16,578 of them). OMIM
publishes no OWL release, so they are left unlabelled on purpose (`-` in
`ontologies.tsv`), as are other databases' records: proteins, genes and InterPro
entries cited in with/from or extensions.

## Column by column

### GPAD 2.0

| Column | Becomes |
|---|---|
| 1 DB_Object_ID | subject of the triple (term) |
| 2 Negation | the node's type: `goann:Annotation`, and the triple is asserted; or `goann:NegatedAnnotation`, and this line does not assert it. Both `rdf:reifies` the triple |
| 3 Relation | predicate of the triple (term) |
| 4 Ontology_Class_ID | object of the triple (term) |
| 5 Reference | `dcterms:source` |
| 6 Evidence_Type | `RO:0002558` (has evidence) → ECO class, which carries `ECO:9000002` (has GO evidence code) |
| 7 With_Or_From | `lego:evidence-with` for every id; `goann:withFromGroup [ rdfs:member … ]` for each `,` group |
| 8 Interacting_Taxon_ID | a `RO:0002233` (has input) restriction in each `goann:extension` class |
| 9 Annotation_Date | `dcterms:date` (`xsd:date` or `xsd:dateTime`) |
| 10 Assigned_By | `pav:providedBy` → the group's IRI, labelled with the abbreviation |
| 11 Annotation_Extensions | `goann:extension`, as above |
| 12 `id=` | `dcterms:identifier` (literal) |
| 12 `comment=go_evidence:XXX` | not written: ECO gives the code (see below) |
| 12 `comment=` other | `rdfs:comment` |
| 12 `contributor-id=` | `dcterms:contributor` (ORCID IRI) |
| 12 `reviewer-id=` / `reviewed-date=` | `pav:curatedBy` / `pav:curatedOn` |
| 12 `creation-date=` / `modification-date=` | `dcterms:created` / `dcterms:modified` |
| 12 `noctua-model-id=` | `prov:wasDerivedFrom` → the GO-CAM model |
| 12 `model-state=` | `lego:modelstate` on the model, once |
| header | a `goann:SourceFile` node: `dcterms:conformsTo` the GPAD/GPI 2.0 spec, `dcterms:creator`, `dcterms:created`, `goann:goVersion` |

GPAD's `go_evidence:` code agrees with ECO's for 15,454,954 of the release's
15,454,965 lines. The other 11 say `go_evidence:NO_MAPPING`, GOA's marker for an ECO
class with no GO code:

- 9 agree with ECO, which gives those classes no code either;
- 2 are on ECO:0007090, which ECO maps to ISS.

The report counts these under `evidence-code.no-mapping` and
`dropped.evidence-code.not-ecos`.

### GPI 2.0

| Column | Becomes |
|---|---|
| 2 DB_Object_Symbol | `rdfs:label` (the name when there is no symbol, as for RNAcentral) |
| 3 DB_Object_Name | `oboInOwl:hasExactSynonym` |
| 4 DB_Object_Synonyms | `oboInOwl:hasRelatedSynonym`, without the symbol and name themselves |
| 5 DB_Object_Type | `rdf:type` (PR, SO or GO class) |
| 6 DB_Object_Taxon | `RO:0002162` (in taxon) |
| 7 Encoded_By | `RO:0002204` (gene product of) |
| 8 Canonical_Object_ID | `goann:canonicalObject`, when it is not the entity itself |
| 9 Protein_Containing_Complex_Members | `RO:0002180` (has component) |
| 10 DB_Xrefs | `rdfs:seeAlso` |
| 11 `db_subset=` | `up:reviewed` (`true` for Swiss-Prot, `false` for TrEMBL) |

## Vocabulary: what is reused, and why

Only seven terms are the KG's own: `goann:Annotation`, `goann:NegatedAnnotation`,
`goann:SourceFile`, `goann:extension`, `goann:withFromGroup`, `goann:canonicalObject`
and `goann:goVersion`. For each, nothing existing fit:

- OBAN is abandoned, and neither Biolink's Association nor GO-CAM's `owl:Axiom` is
  closer;
- GO-CAM keeps a `,` group as one literal string;
- NEO drops the canonical id, and UniProt models isoforms as sequences.

Everything else reuses a precedent, chosen in 2026-11 from Minerva (GO's GO-CAM →
GPAD exporter, which names each GPAD column in RDF), RO, ECO, PAV, PROV and UniProt
core:

| Field | Term | Precedent |
|---|---|---|
| evidence | `RO:0002558` has evidence, to the ECO class | Biolink's `has_evidence` maps to it. RO defines it on evidence instances; GO-CAM makes an instance, and GPAD's class is used directly here, as the main triple already uses classes |
| GO evidence code | `ECO:9000002` on the ECO class | ECO's own property; 1,422 ECO classes carry it |
| with/from | `lego:evidence-with` | GO-CAM. There its values are CURIE strings; here they are IRIs |
| assigned by | `pav:providedBy` → group IRI | GO-CAM, resolved through go-site's `groups` |
| reviewer, review date | `pav:curatedBy`, `pav:curatedOn` | PAV's meaning, the vocabulary of `pav:providedBy` |
| GO-CAM model | `prov:wasDerivedFrom` | Minerva uses PROV for derivation |
| model state | `lego:modelstate`, on the model | GO-CAM |
| interacting taxon | `RO:0002233` restriction | Minerva's GPAD export; GO's `has_primary_input` definitions |
| name, synonyms | `oboInOwl:hasExactSynonym`, `oboInOwl:hasRelatedSynonym` | OBO convention: the name names the same entity; GPI synonyms include ORF names and other loci |
| Swiss-Prot or TrEMBL | `up:reviewed` | UniProt core |
| cross-references | `rdfs:seeAlso` | UniProt's own RDF, for exactly these cross-references |
| format version | `dcterms:conformsTo` the spec | |

**Why not `oboInOwl:hasDbXref` for cross-references.** By convention its value is a
literal CURIE. KGF's key sets and overlap sketches are IRI-only, so literal xrefs
would be invisible to the federation's join discovery. These xrefs (HGNC, MGI,
Ensembl genes) are the bridge to gene-keyed graphs. The SKOS mapping properties were
also considered and rejected: they claim that two concepts match, and these xrefs mix
the same protein in another database, its gene, and OMIM entries.

**`oboInOwl:shorthand` is not used for groups.** It exists for the OBO-to-OWL
conversion; a plain `rdfs:label` carries the abbreviation.

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

**With/from flattened, groups kept.** `lego:evidence-with` lists every identifier, so
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

A local part that still contains `:` under an OBO-style base (one ending in `_`) is
refused rather than written, since it would make a well-formed but wrong IRI. An
identifier with an unlisted prefix is dropped and counted in the run's report, so a
new prefix in a later release shows up there.

**Labels for every external term used, and nothing more of the ontologies.** Per the
OKN convention, reused external terms are encouraged and each gets an `rdfs:label` in
this graph, even though another KG (ubergraph) may label it too: a graph should read
on its own. Only labels of terms the graph uses are included, never whole ontologies;
GO's hierarchy, definitions and synonyms stay in ubergraph. ECO's GO evidence codes
are included for the ECO classes used, about 150 triples, because "no IEA" is the most
common filter on GO annotations. See "Labels" below.

## Size (release 2026-08-05)

From `make convert-all` and `make report` over all 172 `*-uniprot` pairs. Run with
`-j8` on a 12-core laptop, the conversion takes about 110 seconds; the labels step
then takes about 12 minutes, most of it streaming CHEBI, NCBITaxon and PR.

| | |
|---|---:|
| GPAD lines | 15,454,965 |
| positive annotations (`goann:Annotation`) | 15,445,702 |
| NOT annotations (`goann:NegatedAnnotation`) | 9,263 |
| asserted gene product → GO term triples | 11,197,555 |
| triples with only NOT annotations, so never asserted | 7,521 |
| gene products described from the GPI | 1,808,506 |
| annotated objects missing from the GPI | 107,523 |
| extension classes, interacting taxa included | 173,049 |
| triples written | 176,456,203 |
| label triples (`labels.nt.gz`), provenance included | 37,967 |
| N-Triples 1.2 output, gzipped | 1.1 GB |

The asserted-triple count equals the number of distinct positive (object, relation,
term) rows in the source, as checked in DuckDB. No GPAD line was dropped. The report
counts what was dropped below the line level:

- the 102 malformed extension elements above;
- 3 with/from values with no prefix;
- 10,060 GPI xrefs with no prefix;
- 1.34M GPI xrefs dropped on purpose (`GR`, `WGS`, `MGII`);
- the two evidence-code disagreements above.

A KGF bundle of HUMAN alone has 8.89M triples and takes 143 MiB. It builds with
`kgf` 0.6.0 and `hdtc` 1.3.2 in about 20 seconds. In it:

- HUMAN's 1,497 NOT lines state 1,403 distinct triples. The 1,185 of them that no
  positive line asserts sit in `data.hdt.props.hdt`, the side file for unasserted
  triple terms; the other 218 are asserted, so they decode from `data.hdt`;
- `rdf:reifies` counts 919,991, every HUMAN GPAD line. Of those reifiers, 918,494
  are typed `goann:Annotation` and 1,497 `goann:NegatedAnnotation`;
- `?a rdf:reifies <<( ?s RO:0002327 ?o )>>` counts 422,894, every HUMAN *enables*
  line, 618 of them `NOT`;
- `?a rdf:reifies <<( UniProtKB:P04637 ?p ?o )>>` counts 1,049, TP53's GPAD lines
  (it has no `NOT` lines).

All of these match the source rows. Scaled up, the whole release should make a
bundle of under 3 GB.
