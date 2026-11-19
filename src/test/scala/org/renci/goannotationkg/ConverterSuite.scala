package org.renci.goannotationkg

import munit.FunSuite
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.graph.{Graph, Node, NodeFactory}
import org.apache.jena.riot.{Lang, RDFParser}
import org.apache.jena.sparql.graph.GraphFactory

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class ConverterSuite extends FunSuite:
  private val gpad =
    """!gpad-version: 2.0
      |!generated-by: UniProt
      |!date-generated: 2026-07-28 20:25
      |!go-version: http://purl.obolibrary.org/obo/go/releases/2026-07-26/extensions/go-plus.ofn
      |UniProtKB:P1		RO:0002327	GO:0005515	PMID:1	ECO:0000353	UniProtKB:Q1		2026-07-25	IntAct		id=GOA:1|comment=go_evidence:IPI
      |UniProtKB:P1		RO:0002327	GO:0005515	PMID:2	ECO:0000353	UniProtKB:Q2,UniProtKB:Q3|UniProtKB:Q4		2026-07-25	IntAct		id=GOA:2|comment=go_evidence:IPI|contributor-id=https://orcid.org/0000-0002-2979-8613|reviewer-id=https://orcid.org/0000-0001-0000-0001|reviewed-date=2026-01-08
      |UniProtKB:P1	NOT	RO:0002331	GO:0006915	PMID:3	ECO:0000315			2015-01-23	UniProt		id=GOA:3|comment=go_evidence:IMP
      |UniProtKB:P1		RO:0002331	GO:0006915	GO_REF:0000033	ECO:0000318	PANTHER:PTN000290326		2026-01-07	GO_Central	BFO:0000066(GO:0005634),RO:0002233(UniProtKB:Q5)|BFO:0000066(CL:0000540)|has_regulation_target(UniProtKB:Q6)	id=GOA:4|comment=go_evidence:IBA|noctua-model-id=gomodel:abc|model-state=production
      |UniProtKB:P1-2		BFO:0000050	GO:0032991	PMID:4	ECO:0000314		NCBITaxon:taxon:5476	2020-01-01	MGI		id=GOA:5|comment=a free comment
      |RNAcentral:URS000000768F_575584		RO:0002327	GO:0003723	GO_REF:0000115	ECO:0000256	Rfam:RF00005		2026-07-27	RNAcentral		id=GOA:6|comment=go_evidence:IEA
      |FOO:1		RO:0002327	GO:0003723	GO_REF:0000115	ECO:0000256			2026-07-27	RNAcentral		id=GOA:7
      |UniProtKB:P1	maybe	RO:0002327	GO:0003723	GO_REF:0000115	ECO:0000256			2026-07-27	UniProt		id=GOA:8
      |UniProtKB:P2		RO:0002331	GO:0050830	PMID:5	ECO:0000315		NCBITaxon:taxon:1280	2021-03-04	EnsemblFungi	BFO:0000066(CL:0000236),RO:0002211_transport_of(UniProtKB:P01130)|BFO:0000066(CL:0000094)	id=GOA:9|comment=go_evidence:IDA
      |UniProtKB:P2		RO:0002327	GO:0003674	GO_REF:0000015	ECO:0000307			2022-02-02	Others		id=GOA:10|comment=go_evidence:ND|noctua-model-id=gomodel:abc|model-state=production
      |UniProtKB:P2		RO:0001025	GO:0005737	PMID:6	ECO:0000302			2022-02-03	UniProt		id=GOA:11|comment=go_evidence:NO_MAPPING
      |""".stripMargin

  private val gpi =
    """!gpi-version: 2.0
      |!generated-by: UniProt
      |!date-generated: 2026-07-28 22:03
      |UniProtKB:P1	NUDT4B	Diphosphoinositol polyphosphate phosphohydrolase NUDT4B	NUDT4B|DIPP3	PR:000000001	NCBITaxon:9606				EMBL:CH471095|HGNC:HGNC:18012|Ensembl:ENSG00000177144.8|GR:X1	db_subset=Swiss-Prot
      |UniProtKB:P2	ABC	Some protein		PR:000000001	NCBITaxon:9606					db_subset=TrEMBL
      |UniProtKB:P9	UNUSED	Not annotated		PR:000000001	NCBITaxon:9606					db_subset=TrEMBL
      |""".stripMargin

  private val groupsTtl =
    """@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      |@prefix oio: <http://www.geneontology.org/formats/oboInOwl#> .
      |<https://www.ebi.ac.uk/intact> oio:shorthand "IntAct" ; rdfs:label "IntAct" .
      |<https://www.uniprot.org> oio:shorthand "UniProt" ; rdfs:label "UniProt" .
      |<http://geneontology.org> oio:shorthand "GO_Central" ; rdfs:label "GO Central" .
      |<http://informatics.jax.org> oio:shorthand "MGI" ; rdfs:label "MGI" .
      |<http://rnacentral.org> oio:shorthand "RNAcentral" ; rdfs:label "RNAcentral" .
      |""".stripMargin

  private val dbXrefsJson =
    """[{"database": "EnsemblFungi", "name": "Ensembl Fungi", "generic_urls": ["http://fungi.ensembl.org/"]},
      | {"database": "UniProt", "generic_urls": ["http://example.org/should-not-win"]}]""".stripMargin

  // ECO's OWL release, in RDF/XML as published; the axiom annotation mirrors the real file's "Default" marks.
  private val ecoOwl =
    """<?xml version="1.0"?>
      |<rdf:RDF xml:base="http://purl.obolibrary.org/obo/eco.owl"
      |    xmlns:obo="http://purl.obolibrary.org/obo/"
      |    xmlns:owl="http://www.w3.org/2002/07/owl#"
      |    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
      |    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
      |  <owl:Ontology rdf:about="http://purl.obolibrary.org/obo/eco.owl">
      |    <owl:versionIRI rdf:resource="http://purl.obolibrary.org/obo/eco/releases/2026-07-10/eco.owl"/>
      |  </owl:Ontology>
      |  <owl:Class rdf:about="http://purl.obolibrary.org/obo/ECO_0000353"><obo:ECO_9000002>IPI</obo:ECO_9000002></owl:Class>
      |  <owl:Class rdf:about="http://purl.obolibrary.org/obo/ECO_0000315"><obo:ECO_9000002>IMP</obo:ECO_9000002></owl:Class>
      |  <owl:Class rdf:about="http://purl.obolibrary.org/obo/ECO_0000318"><obo:ECO_9000002>IBA</obo:ECO_9000002></owl:Class>
      |  <owl:Class rdf:about="http://purl.obolibrary.org/obo/ECO_0000314"><obo:ECO_9000002>IDA</obo:ECO_9000002></owl:Class>
      |  <owl:Class rdf:about="http://purl.obolibrary.org/obo/ECO_0000256"><obo:ECO_9000002>IEA</obo:ECO_9000002></owl:Class>
      |  <owl:Class rdf:about="http://purl.obolibrary.org/obo/ECO_0000307"><obo:ECO_9000002>ND</obo:ECO_9000002></owl:Class>
      |  <owl:Axiom>
      |    <owl:annotatedSource rdf:resource="http://purl.obolibrary.org/obo/ECO_0000256"/>
      |    <owl:annotatedProperty rdf:resource="http://purl.obolibrary.org/obo/ECO_9000002"/>
      |    <owl:annotatedTarget>IEA</owl:annotatedTarget>
      |    <rdfs:comment>Default</rdfs:comment>
      |  </owl:Axiom>
      |</rdf:RDF>
      |""".stripMargin

  private val releaseDoiJson = """{"doi": "10.5281/zenodo.21844811"}"""
  private val release = "https://release.geneontology.org/2026-08-05"
  private val datasetIri = "https://purl.org/okn/frink/kg/go-annotations"

  private val prefixes = PrefixTable.load(Path.of("prefixes.tsv"))

  private def iri(curie: String): Node = prefixes.expand(curie) match
    case Expansion.Iri(value) => NodeFactory.createURI(value)
    case other => fail(s"cannot expand $curie: $other")
  private def u(value: String): Node = NodeFactory.createURI(value)
  private def lit(value: String): Node = NodeFactory.createLiteralString(value)
  private def typed(value: String, datatype: XSDDatatype): Node = NodeFactory.createLiteralDT(value, datatype)
  private val goann = Vocab.GoAnn
  private val rdf = Vocab.Rdf

  private final case class Run(graph: Graph, stats: Stats, text: String)

  private def run(
      gpadText: String = gpad,
      gpiText: Option[String] = Some(gpi),
      strict: Boolean = false,
      metadata: Boolean = true,
      releaseDoi: Boolean = true,
  ): Run =
    val dir = Files.createTempDirectory("goakg-test")
    def write(name: String, text: String): String =
      val file = dir.resolve(name)
      Files.writeString(file, text, UTF_8)
      file.toString
    val output = dir.resolve("out.nt")
    val stats = Main.run(
      Config(
        prefixes = "prefixes.tsv",
        gpad = write("HUMAN-uniprot.gpad", gpadText),
        gpi = gpiText.map(write("HUMAN-uniprot.gpi", _)),
        gpadSource = Some(s"$release/annotations/gpad/HUMAN-uniprot.gpad.gz"),
        gpiSource = gpiText.map(_ => s"$release/annotations/gpi/HUMAN-uniprot.gpi.gz"),
        groups = Option.when(metadata)(write("groups.ttl", groupsTtl)),
        groupsSource = Option.when(metadata)(s"$release/metadata/groups.ttl"),
        dbXrefs = Option.when(metadata)(write("db-xrefs.json", dbXrefsJson)),
        eco = Option.when(metadata)(write("eco.owl", ecoOwl)),
        ecoSource = Option.when(metadata)("http://purl.obolibrary.org/obo/eco.owl"),
        goRelease = Some("2026-08-05"),
        goReleaseDoi = Option.when(releaseDoi)(write("release-archive-doi.json", releaseDoiJson)),
        datasetIri = Some(datasetIri),
        converterVersion = Some("621b65a"),
        output = output.toString,
        strict = strict,
        quiet = true,
      )
    )
    val text = Files.readString(output, UTF_8)
    val graph = GraphFactory.createDefaultGraph()
    RDFParser.fromString(text, Lang.NTRIPLES).parse(graph)
    Run(graph, stats, text)

  private lazy val standard = run()

  private def objects(g: Graph, s: Node, p: Node): Set[Node] = g.find(s, p, Node.ANY).asScala.map(_.getObject).toSet
  private def subjects(g: Graph, p: Node, o: Node): Set[Node] = g.find(Node.ANY, p, o).asScala.map(_.getSubject).toSet
  private def tt(s: Node, p: Node, o: Node): Node = NodeFactory.createTripleTerm(s, p, o)
  private def annotation(g: Graph, goaId: String): Node = subjects(g, Vocab.Dcterms.identifier, lit(goaId)).head

  /** Each extension class of an annotation, as its set of (relation, target) restrictions. */
  private def extensionClasses(g: Graph, node: Node): Set[Set[(Node, Node)]] =
    objects(g, node, goann.extension).map { c =>
      objects(g, c, Vocab.Rdfs.subClassOf).map(e => (objects(g, e, Vocab.Owl.onProperty).head, objects(g, e, Vocab.Owl.someValuesFrom).head))
    }

  test("asserts each positive gene product → term triple once, reified once per GPAD line"):
    val g = standard.graph
    val (p1, enables, binding) = (iri("UniProtKB:P1"), iri("RO:0002327"), iri("GO:0005515"))
    assert(g.contains(p1, enables, binding))
    val reifiers = subjects(g, rdf.reifies, tt(p1, enables, binding))
    assertEquals(reifiers.size, 2)
    reifiers.foreach(r => assert(g.contains(r, rdf.`type`, goann.Annotation)))
    assertEquals(reifiers.flatMap(objects(g, _, Vocab.Dcterms.source)), Set(iri("PMID:1"), iri("PMID:2")))

  test("NOT annotations reify their triple, typed as negated"):
    val g = standard.graph
    val (p1, involved, apoptosis) = (iri("UniProtKB:P1"), iri("RO:0002331"), iri("GO:0006915"))
    val reifiers = subjects(g, rdf.reifies, tt(p1, involved, apoptosis))
    assertEquals(reifiers.size, 2) // the NOT line and a positive IBA line
    val negated = reifiers.filter(r => g.contains(r, rdf.`type`, goann.NegatedAnnotation))
    assertEquals(negated.size, 1)
    assertEquals(objects(g, negated.head, Vocab.Ro.hasEvidence), Set(iri("ECO:0000315")))
    // The triple is asserted only because the positive IBA line also states it.
    assert(g.contains(p1, involved, apoptosis))

  test("a NOT-only triple is reified but never asserted"):
    val g = run(gpad.linesIterator.filterNot(l => l.contains("GO_Central")).mkString("", "\n", "\n")).graph
    val (p1, involved, apoptosis) = (iri("UniProtKB:P1"), iri("RO:0002331"), iri("GO:0006915"))
    assert(!g.contains(p1, involved, apoptosis))
    val reifiers = subjects(g, rdf.reifies, tt(p1, involved, apoptosis))
    assertEquals(reifiers.size, 1)
    assert(g.contains(reifiers.head, rdf.`type`, goann.NegatedAnnotation))

  test("every reifier has exactly one of the two annotation types"):
    val g = standard.graph
    val reifiers = g.find(Node.ANY, rdf.reifies, Node.ANY).asScala.map(_.getSubject).toSet
    assertEquals(reifiers.size, 9) // every converted GPAD line
    reifiers.foreach { r =>
      val kinds = objects(g, r, rdf.`type`).intersect(Set(goann.Annotation, goann.NegatedAnnotation))
      assertEquals(kinds.size, 1, r.toString)
    }

  test("writes evidence, reference, date, contributors and curation on the annotation"):
    val g = standard.graph
    val r = annotation(g, "GOA:2")
    assertEquals(objects(g, r, Vocab.Ro.hasEvidence), Set(iri("ECO:0000353")))
    assertEquals(objects(g, r, Vocab.Dcterms.date), Set(typed("2026-07-25", XSDDatatype.XSDdate)))
    assertEquals(objects(g, r, Vocab.Dcterms.contributor), Set(u("https://orcid.org/0000-0002-2979-8613")))
    assertEquals(objects(g, r, Vocab.Pav.curatedBy), Set(u("https://orcid.org/0000-0001-0000-0001")))
    assertEquals(objects(g, r, Vocab.Pav.curatedOn), Set(typed("2026-01-08", XSDDatatype.XSDdate)))
    assertEquals(objects(g, annotation(g, "GOA:5"), Vocab.Rdfs.comment), Set(lit("a free comment")))

  test("links GO-CAM-derived annotations to their model, and states the model's state once on the model"):
    val result = standard
    val g = result.graph
    val model = u("http://model.geneontology.org/abc")
    assertEquals(objects(g, annotation(g, "GOA:4"), Vocab.Prov.wasDerivedFrom), Set(model))
    assertEquals(objects(g, annotation(g, "GOA:10"), Vocab.Prov.wasDerivedFrom), Set(model))
    assertEquals(g.find(model, Vocab.Lego.modelstate, Node.ANY).toList.size, 1)
    assertEquals(objects(g, model, Vocab.Lego.modelstate), Set(lit("production")))
    assert(!result.text.contains("modelState"))

  test("assigned_by becomes the group's IRI, labelled with the abbreviation"):
    val g = standard.graph
    val intact = u("https://www.ebi.ac.uk/intact")
    assertEquals(objects(g, annotation(g, "GOA:1"), Vocab.Pav.providedBy), Set(intact))
    assertEquals(objects(g, intact, Vocab.Rdfs.label), Set(lit("IntAct")))
    assert(objects(g, intact, Vocab.OboInOwl.hasExactSynonym).isEmpty)
    val goCentral = u("http://geneontology.org")
    assertEquals(objects(g, annotation(g, "GOA:4"), Vocab.Pav.providedBy), Set(goCentral))
    assertEquals(objects(g, goCentral, Vocab.Rdfs.label), Set(lit("GO_Central")))
    assertEquals(objects(g, goCentral, Vocab.OboInOwl.hasExactSynonym), Set(lit("GO Central")))

  test("falls back to db-xrefs' homepage, then to the abbreviation itself"):
    val result = standard
    val g = result.graph
    val fungi = u("http://fungi.ensembl.org/")
    assertEquals(objects(g, annotation(g, "GOA:9"), Vocab.Pav.providedBy), Set(fungi))
    assertEquals(objects(g, fungi, Vocab.Rdfs.label), Set(lit("EnsemblFungi")))
    assertEquals(objects(g, annotation(g, "GOA:3"), Vocab.Pav.providedBy), Set(u("https://www.uniprot.org"))) // groups.ttl wins
    assertEquals(objects(g, annotation(g, "GOA:10"), Vocab.Pav.providedBy), Set(lit("Others")))
    assertEquals(result.stats("unresolved.assigned-by.Others"), 1L)

  test("GO evidence codes come from ECO, on the ECO classes the file uses"):
    val result = standard
    val g = result.graph
    assertEquals(objects(g, iri("ECO:0000353"), Vocab.Eco.hasGoEvidenceCode), Set(lit("IPI")))
    assertEquals(objects(g, iri("ECO:0000256"), Vocab.Eco.hasGoEvidenceCode), Set(lit("IEA")))
    assertEquals(g.find(Node.ANY, Vocab.Eco.hasGoEvidenceCode, Node.ANY).toList.size, 6)
    assert(!result.text.contains("evidenceCode"))
    // GOA:9 says IDA for ECO:0000315, which ECO calls IMP.
    assertEquals(result.stats("dropped.evidence-code.not-ecos"), 1L)
    // GOA:11's NO_MAPPING agrees with ECO, which gives ECO:0000302 no code; it is not kept as a comment.
    assertEquals(result.stats("evidence-code.no-mapping"), 1L)
    assertEquals(result.stats("eco.without-go-code"), 1L)
    assert(objects(g, annotation(g, "GOA:11"), Vocab.Rdfs.comment).isEmpty)
    // ECO is named by its own owl:versionIRI, not the URL it was downloaded from.
    val eco = u("http://purl.obolibrary.org/obo/eco/releases/2026-07-10/eco.owl")
    assertEquals(objects(g, eco, rdf.`type`), Set(goann.SourceFile))
    assertEquals(objects(g, eco, Vocab.Pav.version), Set(lit("2026-07-10")))
    assert(g.find(u("http://purl.obolibrary.org/obo/eco.owl"), Node.ANY, Node.ANY).toList.isEmpty)

  test("without ECO, the GO codes are counted as dropped"):
    val result = run(metadata = false)
    assertEquals(result.stats("dropped.evidence-code.no-eco-loaded"), 8L)
    assertEquals(objects(result.graph, annotation(result.graph, "GOA:1"), Vocab.Pav.providedBy), Set(lit("IntAct")))

  test("lists every with/from identifier with lego:evidence-with, and groups ','-joined ones"):
    val g = standard.graph
    val r = annotation(g, "GOA:2")
    assertEquals(objects(g, r, Vocab.Lego.evidenceWith), Set("Q2", "Q3", "Q4").map(a => iri(s"UniProtKB:$a")))
    val groups = objects(g, r, goann.withFromGroup)
    assertEquals(groups.size, 1)
    assertEquals(objects(g, groups.head, Vocab.Rdfs.member), Set(iri("UniProtKB:Q2"), iri("UniProtKB:Q3")))

  test("encodes each extension conjunction as a class of someValuesFrom restrictions"):
    val result = standard
    assertEquals(
      extensionClasses(result.graph, annotation(result.graph, "GOA:4")),
      Set(
        Set((iri("BFO:0000066"), iri("GO:0005634")), (iri("RO:0002233"), iri("UniProtKB:Q5"))),
        Set((iri("BFO:0000066"), iri("CL:0000540"))),
      ),
    )
    assertEquals(result.stats("dropped.extension.conjunction"), 1L) // has_regulation_target
    assertEquals(result.stats("dropped.extension.malformed"), 2L) // that, and GOA:9's RO:0002211_transport_of

  test("an interacting taxon becomes a has input restriction in every conjunction"):
    val g = standard.graph
    val hasInput = iri("RO:0002233")
    val candida = u("http://purl.obolibrary.org/obo/NCBITaxon_5476") // written NCBITaxon:taxon:5476
    assertEquals(extensionClasses(g, annotation(g, "GOA:5")), Set(Set((hasInput, candida))))
    val aureus = u("http://purl.obolibrary.org/obo/NCBITaxon_1280")
    assertEquals(
      extensionClasses(g, annotation(g, "GOA:9")),
      Set(
        Set((iri("BFO:0000066"), iri("CL:0000236")), (hasInput, aureus)),
        Set((iri("BFO:0000066"), iri("CL:0000094")), (hasInput, aureus)),
      ),
    )

  test("GO annotation relations appear only between gene products and GO terms"):
    val g = standard.graph
    val relations = Set("RO:0002327", "RO:0002331", "BFO:0000050").map(iri)
    val go = "http://purl.obolibrary.org/obo/GO_"
    g.find().asScala.filter(t => relations(t.getPredicate)).foreach { t =>
      assert(t.getSubject.isURI && t.getObject.isURI && t.getObject.getURI.startsWith(go), t.toString)
    }

  test("describes annotated gene products from the GPI, and only those"):
    val g = standard.graph
    val p1 = iri("UniProtKB:P1")
    assertEquals(objects(g, p1, rdf.`type`), Set(iri("PR:000000001")))
    assertEquals(objects(g, p1, Vocab.Rdfs.label), Set(lit("NUDT4B")))
    assertEquals(objects(g, p1, Vocab.OboInOwl.hasExactSynonym), Set(lit("Diphosphoinositol polyphosphate phosphohydrolase NUDT4B")))
    assertEquals(objects(g, p1, Vocab.OboInOwl.hasRelatedSynonym), Set(lit("DIPP3")))
    assertEquals(objects(g, p1, Vocab.Ro.inTaxon), Set(iri("NCBITaxon:9606")))
    assertEquals(objects(g, p1, Vocab.UniProt.reviewed), Set(typed("true", XSDDatatype.XSDboolean)))
    assertEquals(objects(g, iri("UniProtKB:P2"), Vocab.UniProt.reviewed), Set(typed("false", XSDDatatype.XSDboolean)))
    assertEquals(
      objects(g, p1, Vocab.Rdfs.seeAlso),
      Set(iri("EMBL:CH471095"), iri("HGNC:18012"), u("http://identifiers.org/ensembl/ENSG00000177144")),
    )
    assert(g.find(iri("UniProtKB:P9"), Node.ANY, Node.ANY).toList.isEmpty)

  test("derives what it can for annotated objects missing from the GPI"):
    val g = standard.graph
    val isoform = iri("UniProtKB:P1-2")
    assertEquals(isoform.getURI, "http://purl.uniprot.org/isoforms/P1-2")
    assertEquals(objects(g, isoform, goann.canonicalObject), Set(iri("UniProtKB:P1")))
    assertEquals(objects(g, isoform, Vocab.Ro.inTaxon), Set(iri("NCBITaxon:9606")))
    assertEquals(objects(g, iri("RNAcentral:URS000000768F_575584"), Vocab.Ro.inTaxon), Set(iri("NCBITaxon:575584")))

  test("records the source file's header"):
    val g = standard.graph
    val file = u("https://release.geneontology.org/2026-08-05/annotations/gpad/HUMAN-uniprot.gpad.gz")
    assertEquals(objects(g, file, rdf.`type`), Set(goann.SourceFile))
    assertEquals(objects(g, file, Vocab.Dcterms.conformsTo), Set(u(Vocab.GpadGpiSpec)))
    assertEquals(objects(g, file, goann.goVersion), Set(u("http://purl.obolibrary.org/obo/go/releases/2026-07-26/extensions/go-plus.ofn")))
    assertEquals(objects(g, file, Vocab.Dcterms.created).head.getLiteralLexicalForm, "2026-07-28T20:25:00")

  test("identifies the GO release by its archive DOI, with its files as parts"):
    val g = standard.graph
    val doi = u("https://doi.org/10.5281/zenodo.21844811")
    assertEquals(objects(g, doi, rdf.`type`), Set(Vocab.Dcat.Dataset))
    assertEquals(objects(g, doi, Vocab.Pav.version), Set(lit("2026-08-05")))
    assertEquals(objects(g, doi, Vocab.Rdfs.label), Set(lit("Gene Ontology release 2026-08-05")))
    assertEquals(
      objects(g, doi, Vocab.Dcterms.hasPart),
      Set(
        u(s"$release/annotations/gpad/HUMAN-uniprot.gpad.gz"),
        u(s"$release/annotations/gpi/HUMAN-uniprot.gpi.gz"),
        u(s"$release/metadata/groups.ttl"),
      ),
    )

  test("says what the dataset was derived from and created with"):
    val g = standard.graph
    val dataset = u(datasetIri)
    assertEquals(objects(g, dataset, Vocab.Pav.version), Set(lit("2026-08-05")))
    assertEquals(
      objects(g, dataset, Vocab.Prov.wasDerivedFrom),
      Set(u("https://doi.org/10.5281/zenodo.21844811"), u("http://purl.obolibrary.org/obo/eco/releases/2026-07-10/eco.owl")),
    )
    val tool = u(Vocab.ProjectIri)
    assertEquals(objects(g, dataset, Vocab.Pav.createdWith), Set(tool))
    assertEquals(objects(g, tool, Vocab.Pav.version), Set(lit("621b65a")))

  test("without a DOI, the release is its snapshot URL"):
    val g = run(releaseDoi = false).graph
    val snapshot = u(s"$release/")
    assertEquals(objects(g, snapshot, Vocab.Pav.version), Set(lit("2026-08-05")))
    assert(objects(g, u(datasetIri), Vocab.Prov.wasDerivedFrom).contains(snapshot))

  test("uses only the goann terms that have no existing equivalent"):
    val used = standard.graph.find().asScala.flatMap(t => Seq(t.getPredicate, t.getObject)).filter(_.isURI)
      .map(_.getURI).filter(_.startsWith(Vocab.GoAnnNamespace)).map(_.stripPrefix(Vocab.GoAnnNamespace)).toSet
    assertEquals(used, Set("Annotation", "NegatedAnnotation", "SourceFile", "extension", "withFromGroup", "canonicalObject", "goVersion"))

  test("counts dropped lines and annotations, and --strict refuses them"):
    val stats = standard.stats
    assertEquals(stats("dropped.gpad-line"), 1L) // negation 'maybe'
    assertEquals(stats("dropped.annotation"), 1L) // FOO:1
    assertEquals(stats("annotations.positive"), 8L)
    assertEquals(stats("annotations.negated"), 1L)
    intercept[ConversionException](run(strict = true))

  test("refuses files that are not GPAD/GPI 2.0"):
    intercept[ConversionException](run(gpadText = gpad.replace("!gpad-version: 2.0", "!gaf-version: 2.2")))
    intercept[ConversionException](run(gpiText = Some(gpi.replace("2.0", "1.2"))))

  test("labels blank nodes by species and line, so outputs are reproducible"):
    val a = run().text
    val b = run().text
    assertEquals(a, b)
    assert(a.contains("_:BHUMANa5 "), a.linesIterator.take(3).mkString("\n"))
