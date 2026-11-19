package org.renci.goannotationkg

import munit.FunSuite
import org.apache.jena.graph.{Graph, Node, NodeFactory}
import org.apache.jena.riot.{Lang, RDFParser}
import org.apache.jena.sparql.graph.GraphFactory

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class LabelsSuite extends FunSuite:
  private def owl(versionIri: String, body: String): String =
    s"""<?xml version="1.0"?>
       |<rdf:RDF xmlns:obo="http://purl.obolibrary.org/obo/"
       |    xmlns:owl="http://www.w3.org/2002/07/owl#"
       |    xmlns:oboInOwl="http://www.geneontology.org/formats/oboInOwl#"
       |    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
       |    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
       |  <owl:Ontology rdf:about="http://purl.obolibrary.org/obo/x.owl">
       |    <owl:versionIRI rdf:resource="$versionIri"/>
       |  </owl:Ontology>
       |$body
       |</rdf:RDF>
       |""".stripMargin

  private val goOwl = owl(
    "http://purl.obolibrary.org/obo/go/releases/2026-08-05/go.owl",
    """  <owl:Class rdf:about="http://purl.obolibrary.org/obo/GO_0005515"><rdfs:label>protein binding</rdfs:label></owl:Class>
      |  <owl:Class rdf:about="http://purl.obolibrary.org/obo/GO_0016020">
      |    <rdfs:label xml:lang="en">membrane</rdfs:label>
      |    <oboInOwl:hasAlternativeId>GO:0016021</oboInOwl:hasAlternativeId>
      |  </owl:Class>
      |  <owl:Class rdf:about="http://purl.obolibrary.org/obo/GO_0099999"><rdfs:label>not used</rdfs:label></owl:Class>""".stripMargin,
  )

  // RO also carries GO's term, as an import would: its copy of the label must not be used.
  private val roOwl = owl(
    "http://purl.obolibrary.org/obo/ro/releases/2026-06-01/ro.owl",
    """  <owl:ObjectProperty rdf:about="http://purl.obolibrary.org/obo/RO_0002327"><rdfs:label>enables</rdfs:label></owl:ObjectProperty>
      |  <owl:ObjectProperty rdf:about="http://purl.obolibrary.org/obo/BFO_0000050"><rdfs:label>part of</rdfs:label></owl:ObjectProperty>
      |  <owl:Class rdf:about="http://purl.obolibrary.org/obo/GO_0005515"><rdfs:label>an old import's label</rdfs:label></owl:Class>""".stripMargin,
  )

  private val gorefs =
    """- id: GO_REF:0000002
      |  title: Gene Ontology annotation through association of InterPro records with GO
      |    terms
      |  alt_id:
      |    - GO_REF:0000007
      |- id: GO_REF:0000033
      |  title: Annotation inferences using phylogenetic trees
      |""".stripMargin

  private val terms = Seq(
    "http://purl.obolibrary.org/obo/GO_0005515",
    "http://purl.obolibrary.org/obo/GO_0016020",
    "http://purl.obolibrary.org/obo/GO_0016021",
    "http://purl.obolibrary.org/obo/RO_0002327",
    "http://purl.obolibrary.org/obo/BFO_0000050",
    "http://purl.obolibrary.org/obo/CL_0000540",
    "http://purl.obolibrary.org/obo/go/references/0000033",
    "http://purl.obolibrary.org/obo/go/references/0000007",
    "http://purl.obolibrary.org/obo/go/releases/2026-07-26/extensions/go-plus.ofn",
    "http://purl.obolibrary.org/obo/OMIM_614006",
  )

  private final case class Run(graph: Graph, stats: Stats, unlabelled: Seq[String])

  private def run(): Run =
    val dir = Files.createTempDirectory("goakg-labels")
    def write(name: String, text: String): String =
      val file = dir.resolve(name)
      Files.writeString(file, text, UTF_8)
      file.toString
    write("go.owl", goOwl)
    write("ro.owl", roOwl)
    val ontologies = write(
      "ontologies.tsv",
      "# test\nnamespaces\tsource\tnote\nGO\t{release}/go.owl\nRO,BFO\t" + dir.resolve("ro.owl") +
        "\nPR\tnever-read.owl\tno PR term is used\nOMIM\t-\tcross-references only\n",
    )
    // Split across two term lists, as two species would write them.
    val first = write("A.terms.txt", terms.take(4).mkString("\n") + "\n")
    val second = write("B.terms.txt", terms.drop(3).mkString("\n") + "\n")
    val output = dir.resolve("labels.nt")
    val stats = Labels.run(
      Labels.Config(
        terms = Seq(first, second),
        ontologies = ontologies,
        releaseUrl = Some(dir.toString),
        gorefs = Some(write("gorefs.yaml", gorefs)),
        gorefsSource = Some("https://release.geneontology.org/2026-08-05/metadata/gorefs.yaml"),
        datasetIri = Some("https://purl.org/okn/frink/kg/go-annotations"),
        output = output.toString,
        unlabelled = Some(dir.resolve("unlabelled.tsv").toString),
      )
    )
    val graph = GraphFactory.createDefaultGraph()
    RDFParser.source(output.toString).lang(Lang.NTRIPLES).parse(graph)
    Run(graph, stats, Files.readAllLines(dir.resolve("unlabelled.tsv"), UTF_8).asScala.toSeq)

  private lazy val result = run()
  private def u(value: String): Node = NodeFactory.createURI(value)
  private def labels(term: String): Set[Node] =
    result.graph.find(u(term), Vocab.Rdfs.label, Node.ANY).asScala.map(_.getObject).toSet

  test("labels each used term from its own ontology, keeping language tags"):
    assertEquals(labels("http://purl.obolibrary.org/obo/GO_0005515"), Set(NodeFactory.createLiteralString("protein binding")))
    assertEquals(labels("http://purl.obolibrary.org/obo/GO_0016020"), Set(NodeFactory.createLiteralLang("membrane", "en")))
    assertEquals(labels("http://purl.obolibrary.org/obo/RO_0002327"), Set(NodeFactory.createLiteralString("enables")))
    assertEquals(labels("http://purl.obolibrary.org/obo/BFO_0000050"), Set(NodeFactory.createLiteralString("part of")))

  test("labels only terms the release uses"):
    assert(labels("http://purl.obolibrary.org/obo/GO_0099999").isEmpty)
    val subjects = result.graph.find(Node.ANY, Vocab.Rdfs.label, Node.ANY).asScala.map(_.getSubject.getURI).toSet
    assertEquals(subjects.size, 6) // 4 ontology terms and 2 GO_REFs

  test("gives GO_REFs their titles, alt_ids included"):
    assertEquals(
      labels("http://purl.obolibrary.org/obo/go/references/0000007"),
      Set(NodeFactory.createLiteralString("Gene Ontology annotation through association of InterPro records with GO terms")),
    )
    assertEquals(labels("http://purl.obolibrary.org/obo/go/references/0000033"), Set(NodeFactory.createLiteralString("Annotation inferences using phylogenetic trees")))

  test("reports what it cannot label, naming merged GO ids' current terms"):
    val report = result.stats.report
    assert(report.contains("unlabelled.GO\t1\thttp://purl.obolibrary.org/obo/GO_0016021 (an alt_id of http://purl.obolibrary.org/obo/GO_0016020)"), report)
    assert(report.contains("unlabelled.CL\t1\tno source for CL"), report)
    assertEquals(result.stats("labelled.GO"), 2L)
    assertEquals(result.stats("used.GO"), 3L)

  test("lists every unlabelled term, and skips namespaces marked -"):
    assertEquals(
      result.unlabelled,
      Seq(
        "http://purl.obolibrary.org/obo/CL_0000540\tno source for CL",
        "http://purl.obolibrary.org/obo/GO_0016021\tan alt_id of http://purl.obolibrary.org/obo/GO_0016020",
      ),
    )
    assertEquals(result.stats("skipped.OMIM"), 1L)
    assertEquals(result.stats("unlabelled.OMIM"), 0L)

  test("records each ontology read by its version IRI, as a source of the dataset"):
    val g = result.graph
    val dataset = u("https://purl.org/okn/frink/kg/go-annotations")
    val go = u("http://purl.obolibrary.org/obo/go/releases/2026-08-05/go.owl")
    val ro = u("http://purl.obolibrary.org/obo/ro/releases/2026-06-01/ro.owl")
    val gorefsFile = u("https://release.geneontology.org/2026-08-05/metadata/gorefs.yaml")
    assertEquals(g.find(dataset, Vocab.Prov.wasDerivedFrom, Node.ANY).asScala.map(_.getObject).toSet, Set(go, ro, gorefsFile))
    assertEquals(g.find(go, Vocab.Pav.version, Node.ANY).asScala.map(_.getObject).toSet, Set(NodeFactory.createLiteralString("2026-08-05")))
    assert(g.contains(ro, Vocab.Rdf.`type`, Vocab.GoAnn.SourceFile))

  test("reads only the ontologies whose terms are used"):
    // PR's source does not exist; reading it would have failed the run.
    assertEquals(result.stats("used.PR"), 0L)

  test("refuses an ontologies table without its header, or with a namespace twice"):
    val dir = Files.createTempDirectory("goakg-labels-bad")
    val noHeader = dir.resolve("a.tsv")
    Files.writeString(noHeader, "GO\tgo.owl\n", UTF_8)
    intercept[ConversionException](Labels.loadSources(noHeader, None, Map.empty))
    val twice = dir.resolve("b.tsv")
    Files.writeString(twice, "namespaces\tsource\tnote\nGO\ta.owl\nGO,RO\tb.owl\n", UTF_8)
    intercept[ConversionException](Labels.loadSources(twice, None, Map.empty))
    val needsRelease = dir.resolve("c.tsv")
    Files.writeString(needsRelease, "namespaces\tsource\tnote\nGO\t{release}/go.owl\n", UTF_8)
    intercept[ConversionException](Labels.loadSources(needsRelease, None, Map.empty))

  test("takes a version from the segment before the file name"):
    assertEquals(Labels.versionOf("http://purl.obolibrary.org/obo/eco/releases/2026-07-10/eco.owl"), Some("2026-07-10"))
    assertEquals(Labels.versionOf("http://purl.obolibrary.org/obo/pr/71.0/pr.owl"), Some("71.0"))
