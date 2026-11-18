# go-annotation-kg

Converts a Gene Ontology release's annotations to RDF 1.2 and builds a KGF bundle of
them for the Open Knowledge Network. The aim is one canonical GO annotation graph
in the OKN.

The input is GO's per-species GPAD 2.0 and GPI 2.0 files, the `*-uniprot` pairs only.
The `*-mod` files repeat the same annotations keyed to MOD gene ids. Each gene
product → GO term relation is a plain triple, asserted once. Each GPAD line becomes a
reifier of that triple, carrying the line's evidence, reference, with/from, date,
assigner, extensions and properties. `NOT` lines point at their triple with
`goann:negates` and never assert it. [`docs/model.md`](docs/model.md) has the
model and the reasons for it, and [`docs/queries/`](docs/queries/) has SPARQL 1.2
examples.

| Path | What it is |
|---|---|
| `src/main/scala/` | The converter: one GPAD/GPI pair to N-Triples 1.2, streamed with Jena. |
| `prefixes.tsv` | How every CURIE prefix in the files becomes an IRI, and why. |
| `vocab/goann.ttl` | The `goann:` vocabulary, loaded into every bundle. |
| `kgf/go-annotations.yaml` | The `kgf build` config, until the registry entry is merged. |
| `registry/go-annotations.md` | Draft entry for [okn-registry](https://github.com/frink-okn/okn-registry). |
| `Makefile` | Download, convert, report and bundle one GO release. |

## Running a release

Java 21 and [Scala CLI](https://scala-cli.virtuslab.org) are needed; `kgf` and `hdtc`
are needed for the bundle.

```bash
make test
```

```bash
make -j8 convert-all
```

```bash
make report
```

```bash
make bundle KGF=/path/to/kgf HDTC=/path/to/hdtc
```

`RELEASE` defaults to the current release, from
`https://current.geneontology.org/metadata/release-date.json`. Everything is read
from that release's pinned snapshot at `https://release.geneontology.org/RELEASE/`,
and lands under `data/RELEASE/` (`DATA_ROOT` to move it). Pass `RELEASE=YYYY-MM-DD`
to rebuild an older one. Downloads and conversions are per species and atomic, so an
interrupted run picks up where it stopped. Try one species first with
`make convert SPECIES=HUMAN`.

`make report` sums every species' counters into `data/RELEASE/report.tsv`. Read its
`dropped.*` lines after each release: a prefix GO starts using that
`prefixes.tsv` does not list shows up there as `dropped.*.unknown-prefix.PREFIX`, with
an example. Add the prefix to the table and convert again.

The bundle lands in `data/bundles/go-annotations/RELEASE`. For release 2026-08-05,
expect about 192M triples and a bundle of about 3 GB, so build on a machine with tens
of GB free.

## The converter alone

```bash
scala-cli run . --server=false -- --prefixes prefixes.tsv \
  --gpad HUMAN-uniprot.gpad.gz --gpi HUMAN-uniprot.gpi.gz \
  --report HUMAN.report.tsv --output HUMAN.nt.gz
```

Input compression is detected; output is gzipped when its name ends in `.gz`. The
output is written to a temporary file and moved into place only when the conversion
succeeds. `--strict` fails without output if any GPAD line or annotation was dropped.
`make build` packages `target/go-annotation-kg.jar`, which the Makefile runs.
