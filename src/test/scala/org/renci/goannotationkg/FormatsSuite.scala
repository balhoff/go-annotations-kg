package org.renci.goannotationkg

import munit.FunSuite

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8

class FormatsSuite extends FunSuite:
  private def cols(line: String): Array[String] = line.split("\t", -1)

  test("parses a full GPAD 2.0 line"):
    val a = Formats.parseGpad(
      7,
      cols(
        "UniProtKB:P1\tNOT\tRO:0002327\tGO:0005515\tPMID:1\tECO:0000353\tUniProtKB:Q1,UniProtKB:Q2|UniProtKB:Q3\t" +
          "NCBITaxon:5476\t2019-01-30\tMGI\tBFO:0000066(GO:0005829),RO:0002233(CHEBI:1)|BFO:0000066(CL:1)\t" +
          "id=GOA:1|comment=go_evidence:IPI|contributor-id=https://orcid.org/0000-0002-1478-7671"
      ),
    )
    assertEquals(a.line, 7L)
    assert(a.negated)
    assertEquals(a.withFrom, Vector(Vector("UniProtKB:Q1", "UniProtKB:Q2"), Vector("UniProtKB:Q3")))
    assertEquals(a.interactingTaxa, Vector("NCBITaxon:5476"))
    assertEquals(a.extensions, Vector(Vector("BFO:0000066(GO:0005829)", "RO:0002233(CHEBI:1)"), Vector("BFO:0000066(CL:1)")))
    assertEquals(a.properties.map(_._1), Vector("id", "comment", "contributor-id"))
    assertEquals(a.properties(2)._2, "https://orcid.org/0000-0002-1478-7671")

  test("accepts lines without the trailing optional columns"):
    val a = Formats.parseGpad(1, cols("UniProtKB:P1\t\tRO:0002327\tGO:1\tPMID:1\tECO:1\t\t\t2019-01-30\tMGI"))
    assert(!a.negated)
    assert(a.withFrom.isEmpty && a.extensions.isEmpty && a.properties.isEmpty)

  test("rejects malformed GPAD lines with their line number"):
    val good = "UniProtKB:P1\t\tRO:0002327\tGO:1\tPMID:1\tECO:1\t\t\t2019-01-30\tMGI\t\t"
    val bad = Seq(
      good.replace("\t\tRO", "\tnot\tRO"), // negation
      good.replace("2019-01-30", "30/01/2019"), // date
      good.replace("GO:1", ""), // required column
      good + "\textra", // too many columns
      "UniProtKB:P1\t\tRO:0002327", // too few columns
      good + "noequals", // property without '='
    )
    bad.foreach { line =>
      val error = intercept[LineException](Formats.parseGpad(42, cols(line)))
      assertEquals(error.line, 42L)
    }

  test("parses relational expressions"):
    assertEquals(Formats.parseExpression("RO:0002233(UniProtKB:Q00362)"), Some(RelationalExpression("RO:0002233", "UniProtKB:Q00362")))
    assertEquals(Formats.parseExpression("RO:0002233(UniProtKB:Q00362"), None)
    assertEquals(Formats.parseExpression("RO:0002233"), None)

  test("accepts dates and date-times"):
    assert(Formats.isDateOrDateTime("2019-01-30"))
    assert(Formats.isDateOrDateTime("2019-01-30T12:00:00"))
    assert(Formats.isDateOrDateTime("2019-01-30T12:00:00.5+02:00"))
    assert(!Formats.isDateOrDateTime("20190130"))

  test("parses a GPI 2.0 line, defaulting the canonical id to the entity"):
    val e = Formats.parseGpi(
      3,
      cols("UniProtKB:A0A024RBG1\tNUDT4B\tDiphosphoinositol polyphosphate phosphohydrolase NUDT4B\tNUDT4B\tPR:000000001\tNCBITaxon:9606\t\t\t\tHGNC:HGNC:18012|Ensembl:ENSG00000177144.8\tdb_subset=Swiss-Prot"),
    )
    assertEquals(e.symbol, "NUDT4B")
    assertEquals(e.canonicalId, "UniProtKB:A0A024RBG1")
    assertEquals(e.xrefs, Vector("HGNC:HGNC:18012", "Ensembl:ENSG00000177144.8"))
    assertEquals(e.properties, Vector("db_subset" -> "Swiss-Prot"))

  test("reads headers before rows, keeping file line numbers"):
    val text = "!gpad-version: 2.0\n!generated-by: UniProt\n!!free text\n!date-generated: 2026-07-28 20:25\nrow1\n\nrow2\n"
    val reader = TabularReader(ByteArrayInputStream(text.getBytes(UTF_8)))
    assertEquals(reader.headers, Vector("gpad-version" -> "2.0", "generated-by" -> "UniProt", "date-generated" -> "2026-07-28 20:25"))
    val rows = Vector.newBuilder[(Long, String)]
    reader.foreachRow((n, c) => rows += n -> c.head)
    assertEquals(rows.result(), Vector(5L -> "row1", 7L -> "row2"))
