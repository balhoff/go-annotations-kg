SHELL := /bin/bash
.DELETE_ON_ERROR:
.SECONDARY:

# Everything for one GO release lives under $(WORK):
#   input/gpad/SPECIES-uniprot.gpad.gz   input/gpi/SPECIES-uniprot.gpi.gz
#   input/metadata/groups.ttl, db-xrefs.json   (GO's, from the same release)
#   input/eco.obo                         (ECO, current when first downloaded)
#   rdf/SPECIES.nt.gz  rdf/SPECIES.report.tsv  rdf/vocab.ttl
#   report.tsv                            counters summed over all species
DATA_ROOT ?= data
BUNDLE_ROOT ?= $(DATA_ROOT)/bundles
DATASET ?= go-annotations

RELEASE_URL ?= https://current.geneontology.org/metadata/release-date.json
ifndef RELEASE
RELEASE := $(shell curl -sfL --retry 3 "$(RELEASE_URL)" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' | head -1)
endif
ifeq ($(RELEASE),)
$(error could not read the current GO release from $(RELEASE_URL); pass RELEASE=YYYY-MM-DD)
endif

GO_RELEASE_URL ?= https://release.geneontology.org/$(RELEASE)
ECO_URL ?= http://purl.obolibrary.org/obo/eco.obo
WORK := $(DATA_ROOT)/$(RELEASE)
METADATA := $(WORK)/input/metadata/groups.ttl $(WORK)/input/metadata/db-xrefs.json $(WORK)/input/eco.obo
SPECIES_LIST := $(WORK)/species.txt

SCALA_CLI ?= scala-cli
JAVA_OPTS ?= -Xmx2g
JAR := target/go-annotation-kg.jar
CURL := curl --fail --location --silent --show-error --retry 8 --retry-all-errors

KGF ?= kgf
HDTC ?= hdtc
KGF_CONFIG ?= kgf/go-annotations.yaml

.PHONY: help build test release species download convert-all report bundle clean-build

help:
	@echo 'make test                     Run the converter tests'
	@echo 'make build                    Build the assembly JAR'
	@echo 'make release                  Print the GO release this run uses ($(RELEASE))'
	@echo 'make convert-all [-j N]       Download and convert every *-uniprot GPAD/GPI pair'
	@echo 'make convert SPECIES=HUMAN    Download and convert one species'
	@echo 'make report                   Sum the per-species counters into $(WORK)/report.tsv'
	@echo 'make bundle                   Build the KGF bundle with kgf (needs kgf and hdtc)'
	@echo 'Override RELEASE, DATA_ROOT, BUNDLE_ROOT, KGF, HDTC or KGF_CONFIG as needed.'

build: $(JAR)

$(JAR): project.scala $(shell find src/main -type f)
	@mkdir -p "$(dir $@)"
	$(SCALA_CLI) --power package . --server=false --assembly --force --main-class org.renci.goannotationkg.Main -o "$@"

test:
	$(SCALA_CLI) test . --server=false

release:
	@echo $(RELEASE)

# The species codes are the names of the release's *-uniprot.gpad.gz files.
# The *-mod.gpad.gz files repeat the same annotations keyed to MOD gene ids.
$(SPECIES_LIST):
	@mkdir -p "$(dir $@)"
	$(CURL) "$(GO_RELEASE_URL)/annotations/gpad/index.html" \
	  | grep -oE '[A-Za-z0-9]+-uniprot\.gpad\.gz' | sed 's/-uniprot\.gpad\.gz$$//' | sort -u > "$@.part"
	@test -s "$@.part" || { echo "no *-uniprot.gpad.gz files listed for release $(RELEASE)" >&2; exit 1; }
	mv "$@.part" "$@"

species: $(SPECIES_LIST)
	@wc -l < "$<"

$(WORK)/input/gpad/%-uniprot.gpad.gz:
	@mkdir -p "$(dir $@)"
	$(CURL) -o "$@.part" "$(GO_RELEASE_URL)/annotations/gpad/$*-uniprot.gpad.gz"
	mv "$@.part" "$@"

$(WORK)/input/gpi/%-uniprot.gpi.gz:
	@mkdir -p "$(dir $@)"
	$(CURL) -o "$@.part" "$(GO_RELEASE_URL)/annotations/gpi/$*-uniprot.gpi.gz"
	mv "$@.part" "$@"

# GO's group and database metadata come from the release itself.
$(WORK)/input/metadata/%:
	@mkdir -p "$(dir $@)"
	$(CURL) -o "$@.part" "$(GO_RELEASE_URL)/metadata/$*"
	mv "$@.part" "$@"

# ECO is not part of a GO release: the first download is kept for the release's
# later conversions, and its data-version is recorded in the graph.
$(WORK)/input/eco.obo:
	@mkdir -p "$(dir $@)"
	$(CURL) -o "$@.part" "$(ECO_URL)"
	mv "$@.part" "$@"

$(WORK)/rdf/%.nt.gz: $(WORK)/input/gpad/%-uniprot.gpad.gz $(WORK)/input/gpi/%-uniprot.gpi.gz $(METADATA) prefixes.tsv $(JAR)
	@mkdir -p "$(dir $@)"
	java $(JAVA_OPTS) -jar "$(JAR)" --prefixes prefixes.tsv --species "$*" \
	  --gpad "$(word 1,$^)" --gpad-source "$(GO_RELEASE_URL)/annotations/gpad/$*-uniprot.gpad.gz" \
	  --gpi "$(word 2,$^)" --gpi-source "$(GO_RELEASE_URL)/annotations/gpi/$*-uniprot.gpi.gz" \
	  --groups "$(WORK)/input/metadata/groups.ttl" --groups-source "$(GO_RELEASE_URL)/metadata/groups.ttl" \
	  --db-xrefs "$(WORK)/input/metadata/db-xrefs.json" --db-xrefs-source "$(GO_RELEASE_URL)/metadata/db-xrefs.json" \
	  --eco "$(WORK)/input/eco.obo" --eco-source "$(ECO_URL)" \
	  --report "$(WORK)/rdf/$*.report.tsv" --output "$@"

$(WORK)/rdf/vocab.ttl: vocab/goann.ttl
	@mkdir -p "$(dir $@)"
	cp "$<" "$@"

convert:
	@test -n "$(SPECIES)" || { echo 'SPECIES is required, e.g. make convert SPECIES=HUMAN' >&2; exit 2; }
	$(MAKE) --no-print-directory "$(WORK)/rdf/$(SPECIES).nt.gz"

# Two steps, so that Make knows the species before it plans the conversions.
convert-all: $(SPECIES_LIST) $(JAR) $(METADATA)
	$(MAKE) --no-print-directory RELEASE=$(RELEASE) \
	  $$(sed 's|.*|$(WORK)/rdf/&.nt.gz|' "$(SPECIES_LIST)") "$(WORK)/rdf/vocab.ttl"

# Always rewritten, from whatever species reports exist now.
report: $(SPECIES_LIST)
	@missing=$$(while read -r s; do test -s "$(WORK)/rdf/$$s.report.tsv" || echo "$$s"; done < "$(SPECIES_LIST)"); \
	  test -z "$$missing" || { echo "not converted yet: $$missing" >&2; exit 1; }
	sed 's|.*|$(WORK)/rdf/&.report.tsv|' "$(SPECIES_LIST)" | xargs cat \
	  | awk -F'\t' '{ n[$$1] += $$2; if ($$3 != "" && !($$1 in ex)) ex[$$1] = $$3 } \
	    END { for (k in n) print k "\t" n[k] (k in ex ? "\t" ex[k] : "") }' | sort > "$(WORK)/report.tsv"
	@cat "$(WORK)/report.tsv"

# kgf runs hdtc for every external step; the bundle lands in $(BUNDLE_ROOT)/$(DATASET)/$(RELEASE).
bundle: report
	$(KGF) build --config "$(KGF_CONFIG)" --hdtc "$(HDTC)" --version "$(RELEASE)" \
	  --out "$(BUNDLE_ROOT)/$(DATASET)/$(RELEASE)" \
	  --source-url "$(GO_RELEASE_URL)/annotations/" \
	  $$(sed 's|.*|--input $(WORK)/rdf/&.nt.gz|' "$(SPECIES_LIST)") --input "$(WORK)/rdf/vocab.ttl"

clean-build:
	rm -rf .scala-build target
