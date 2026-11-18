package org.renci.goannotationkg

import munit.FunSuite
import org.apache.jena.graph.{Graph, Node, NodeFactory, Triple}
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
      |UniProtKB:P1		RO:0002327	GO:0005515	PMID:2	ECO:0000353	UniProtKB:Q2,UniProtKB:Q3|UniProtKB:Q4		2026-07-25	IntAct		id=GOA:2|comment=go_evidence:IPI|contributor-id=https://orcid.org/0000-0002-2979-8613
      |UniProtKB:P1	NOT	RO:0002331	GO:0006915	PMID:3	ECO:0000315			2015-01-23	UniProt		id=GOA:3|comment=go_evidence:IMP
      |UniProtKB:P1		RO:0002331	GO:0006915	GO_REF:0000033	ECO:0000318	PANTHER:PTN000290326		2026-01-07	GO_Central	BFO:0000066(GO:0005634),RO:0002233(UniProtKB:Q5)|BFO:0000066(CL:0000540)|has_regulation_target(UniProtKB:Q6)	id=GOA:4|noctua-model-id=gomodel:abc|model-state=production|reviewed-date=2026-01-08
      |UniProtKB:P1-2		BFO:0000050	GO:0032991	PMID:4	ECO:0000314		NCBITaxon:5476	2020-01-01	MGI		id=GOA:5|comment=a free comment
      |RNAcentral:URS000000768F_575584		RO:0002327	GO:0003723	GO_REF:0000115	ECO:0000256	Rfam:RF00005		2026-07-27	RNAcentral		id=GOA:6
      |FOO:1		RO:0002327	GO:0003723	GO_REF:0000115	ECO:0000256			2026-07-27	RNAcentral		id=GOA:7
      |UniProtKB:P1	maybe	RO:0002327	GO:0003723	GO_REF:0000115	ECO:0000256			2026-07-27	UniProt		id=GOA:8
      |""".stripMargin

  private val gpi =
    """!gpi-version: 2.0
      |!generated-by: UniProt
      |!date-generated: 2026-07-28 22:03
      |UniProtKB:P1	NUDT4B	Diphosphoinositol polyphosphate phosphohydrolase NUDT4B	NUDT4B|DIPP3	PR:000000001	NCBITaxon:9606				EMBL:CH471095|HGNC:HGNC:18012|Ensembl:ENSG00000177144.8|GR:X1	db_subset=Swiss-Prot
      |UniProtKB:P9	UNUSED	Not annotated		PR:000000001	NCBITaxon:9606					db_subset=TrEMBL
      |""".stripMargin

  private val prefixes = PrefixTable.load(Path.of("prefixes.tsv"))

  private def iri(curie: String): Node = prefixes.expand(curie) match
    case Expansion.Iri(value) => NodeFactory.createURI(value)
    case other => fail(s"cannot expand $curie: $other")
  private def u(value: String): Node = NodeFactory.createURI(value)
  private def lit(value: String): Node = NodeFactory.createLiteralString(value)
  private val goann = Vocab.GoAnn
  private val rdf = Vocab.Rdf

  private final case class Run(graph: Graph, stats: Stats, text: String)

  private def run(gpadText: String = gpad, gpiText: Option[String] = Some(gpi), strict: Boolean = false): Run =
    val dir = Files.createTempDirectory("goakg-test")
    val gpadFile = dir.resolve("HUMAN-uniprot.gpad")
    Files.writeString(gpadFile, gpadText, UTF_8)
    val gpiFile = gpiText.map { text =>
      val file = dir.resolve("HUMAN-uniprot.gpi")
      Files.writeString(file, text, UTF_8)
      file.toString
    }
    val output = dir.resolve("out.nt")
    val stats = Main.run(
      Config(
        prefixes = "prefixes.tsv",
        gpad = gpadFile.toString,
        gpi = gpiFile,
        gpadSource = Some("https://release.geneontology.org/2026-08-05/annotations/gpad/HUMAN-uniprot.gpad.gz"),
        output = output.toString,
        strict = strict,
        quiet = true,
      )
    )
    val text = Files.readString(output, UTF_8)
    val graph = GraphFactory.createDefaultGraph()
    RDFParser.fromString(text, Lang.NTRIPLES).parse(graph)
    Run(graph, stats, text)

  private def objects(g: Graph, s: Node, p: Node): Set[Node] = g.find(s, p, Node.ANY).asScala.map(_.getObject).toSet
  private def subjects(g: Graph, p: Node, o: Node): Set[Node] = g.find(Node.ANY, p, o).asScala.map(_.getSubject).toSet
  private def tt(s: Node, p: Node, o: Node): Node = NodeFactory.createTripleTerm(s, p, o)

  test("asserts each positive gene product → term triple once, reified once per GPAD line"):
    val g = run().graph
    val (p1, enables, binding) = (iri("UniProtKB:P1"), iri("RO:0002327"), iri("GO:0005515"))
    assert(g.contains(p1, enables, binding))
    val reifiers = subjects(g, rdf.reifies, tt(p1, enables, binding))
    assertEquals(reifiers.size, 2)
    reifiers.foreach(r => assert(g.contains(r, rdf.`type`, goann.Annotation)))
    assertEquals(reifiers.flatMap(objects(g, _, Vocab.Dcterms.source)), Set(iri("PMID:1"), iri("PMID:2")))

  test("NOT annotations reify their triple, typed as negated"):
    val g = run().graph
    val (p1, involved, apoptosis) = (iri("UniProtKB:P1"), iri("RO:0002331"), iri("GO:0006915"))
    val reifiers = subjects(g, rdf.reifies, tt(p1, involved, apoptosis))
    assertEquals(reifiers.size, 2) // the NOT line and a positive IBA line
    val negated = reifiers.filter(r => g.contains(r, rdf.`type`, goann.NegatedAnnotation))
    assertEquals(negated.size, 1)
    assertEquals(objects(g, negated.head, goann.evidenceCode), Set(lit("IMP")))
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
    val g = run().graph
    val reifiers = g.find(Node.ANY, rdf.reifies, Node.ANY).asScala.map(_.getSubject).toSet
    assertEquals(reifiers.size, 6) // every converted GPAD line
    reifiers.foreach { r =>
      val kinds = objects(g, r, rdf.`type`).intersect(Set(goann.Annotation, goann.NegatedAnnotation))
      assertEquals(kinds.size, 1, r.toString)
    }

  test("writes evidence, reference, date, assigner and properties on the annotation"):
    val g = run().graph
    val r = subjects(g, Vocab.Dcterms.identifier, lit("GOA:2")).head
    assertEquals(objects(g, r, Vocab.Ro.hasEvidence), Set(iri("ECO:0000353")))
    assertEquals(objects(g, r, Vocab.Dcterms.date), Set(NodeFactory.createLiteralDT("2026-07-25", org.apache.jena.datatypes.xsd.XSDDatatype.XSDdate)))
    assertEquals(objects(g, r, goann.assignedBy), Set(lit("IntAct")))
    assertEquals(objects(g, r, Vocab.Dcterms.contributor), Set(u("https://orcid.org/0000-0002-2979-8613")))
    val ibaNode = subjects(g, Vocab.Dcterms.identifier, lit("GOA:4")).head
    assertEquals(objects(g, ibaNode, goann.gocamModel), Set(u("http://model.geneontology.org/abc")))
    assertEquals(objects(g, ibaNode, goann.modelState), Set(lit("production")))
    assertEquals(objects(g, ibaNode, goann.reviewed).size, 1)
    val commented = subjects(g, Vocab.Dcterms.identifier, lit("GOA:5")).head
    assertEquals(objects(g, commented, Vocab.Rdfs.comment), Set(lit("a free comment")))
    assertEquals(objects(g, commented, goann.interactingTaxon), Set(iri("NCBITaxon:5476")))

  test("lists every with/from identifier, and groups ','-joined ones"):
    val g = run().graph
    val r = subjects(g, Vocab.Dcterms.identifier, lit("GOA:2")).head
    assertEquals(objects(g, r, goann.withFrom), Set("Q2", "Q3", "Q4").map(a => iri(s"UniProtKB:$a")))
    val groups = objects(g, r, goann.withFromGroup)
    assertEquals(groups.size, 1)
    assertEquals(objects(g, groups.head, Vocab.Rdfs.member), Set(iri("UniProtKB:Q2"), iri("UniProtKB:Q3")))

  test("encodes each extension conjunction as a class of someValuesFrom restrictions"):
    val result = run()
    val g = result.graph
    val r = subjects(g, Vocab.Dcterms.identifier, lit("GOA:4")).head
    val classes = objects(g, r, goann.extension)
    assertEquals(classes.size, 2) // the has_regulation_target conjunction is dropped
    val restrictions = classes.map { c =>
      objects(g, c, Vocab.Rdfs.subClassOf).map(e => (objects(g, e, Vocab.Owl.onProperty).head, objects(g, e, Vocab.Owl.someValuesFrom).head))
    }
    assertEquals(
      restrictions,
      Set(
        Set((iri("BFO:0000066"), iri("GO:0005634")), (iri("RO:0002233"), iri("UniProtKB:Q5"))),
        Set((iri("BFO:0000066"), iri("CL:0000540"))),
      ),
    )
    assertEquals(result.stats("dropped.extension.conjunction"), 1L)

  test("GO annotation relations appear only between gene products and GO terms"):
    val g = run().graph
    val relations = Set("RO:0002327", "RO:0002331", "BFO:0000050").map(iri)
    val go = "http://purl.obolibrary.org/obo/GO_"
    g.find().asScala.filter(t => relations(t.getPredicate)).foreach { t =>
      assert(t.getSubject.isURI && t.getObject.isURI && t.getObject.getURI.startsWith(go), t.toString)
    }

  test("describes annotated gene products from the GPI, and only those"):
    val g = run().graph
    val p1 = iri("UniProtKB:P1")
    assertEquals(objects(g, p1, rdf.`type`), Set(iri("PR:000000001")))
    assertEquals(objects(g, p1, Vocab.Rdfs.label), Set(lit("NUDT4B")))
    assertEquals(objects(g, p1, goann.fullName), Set(lit("Diphosphoinositol polyphosphate phosphohydrolase NUDT4B")))
    assertEquals(objects(g, p1, Vocab.Skos.altLabel), Set(lit("DIPP3")))
    assertEquals(objects(g, p1, Vocab.Ro.inTaxon), Set(iri("NCBITaxon:9606")))
    assertEquals(objects(g, p1, goann.dbSubset), Set(lit("Swiss-Prot")))
    assertEquals(
      objects(g, p1, goann.xref),
      Set(iri("EMBL:CH471095"), iri("HGNC:18012"), u("http://identifiers.org/ensembl/ENSG00000177144")),
    )
    assert(g.find(iri("UniProtKB:P9"), Node.ANY, Node.ANY).toList.isEmpty)

  test("derives what it can for annotated objects missing from the GPI"):
    val g = run().graph
    val isoform = iri("UniProtKB:P1-2")
    assertEquals(isoform.getURI, "http://purl.uniprot.org/isoforms/P1-2")
    assertEquals(objects(g, isoform, goann.canonicalObject), Set(iri("UniProtKB:P1")))
    assertEquals(objects(g, isoform, Vocab.Ro.inTaxon), Set(iri("NCBITaxon:9606")))
    assertEquals(objects(g, iri("RNAcentral:URS000000768F_575584"), Vocab.Ro.inTaxon), Set(iri("NCBITaxon:575584")))

  test("records the source file's header"):
    val g = run().graph
    val file = u("https://release.geneontology.org/2026-08-05/annotations/gpad/HUMAN-uniprot.gpad.gz")
    assertEquals(objects(g, file, rdf.`type`), Set(goann.SourceFile))
    assertEquals(objects(g, file, goann.goVersion), Set(u("http://purl.obolibrary.org/obo/go/releases/2026-07-26/extensions/go-plus.ofn")))
    assertEquals(objects(g, file, Vocab.Dcterms.created).head.getLiteralLexicalForm, "2026-07-28T20:25:00")

  test("counts dropped lines and annotations, and --strict refuses them"):
    val stats = run().stats
    assertEquals(stats("dropped.gpad-line"), 1L) // negation 'maybe'
    assertEquals(stats("dropped.annotation"), 1L) // FOO:1
    assertEquals(stats("annotations.positive"), 5L)
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
