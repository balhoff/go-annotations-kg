package org.renci.goannotationkg

import munit.FunSuite

import java.nio.file.Path

class PrefixTableSuite extends FunSuite:
  private val table = PrefixTable.fromRows(
    Seq(
      ("UniProtKB", "http://purl.uniprot.org/uniprot/", "-"),
      ("UniProtKB.isoform", "http://purl.uniprot.org/isoforms/", "-"),
      ("UniProtKB.chain", "http://purl.uniprot.org/annotation/", "-"),
      ("SPTR", "=UniProtKB", "-"),
      ("MGI", "http://identifiers.org/mgi/", "-"),
      ("HGNC", "http://identifiers.org/hgnc/", "-"),
      ("ENSEMBL", "http://identifiers.org/ensembl/", "strip_version"),
      ("Ensembl", "=ENSEMBL", "-"),
      ("Reactome", "http://www.reactome.org/PathwayBrowser/#/", "-"),
      ("DOI", "https://doi.org/", "-"),
      ("GR", "-", "-"),
    )
  )

  private def iri(curie: String): String = table.expand(curie) match
    case Expansion.Iri(value) => value
    case other => fail(s"expected an IRI for $curie, got $other")

  test("expands a plain CURIE"):
    assertEquals(iri("UniProtKB:P12345"), "http://purl.uniprot.org/uniprot/P12345")

  test("routes UniProtKB isoforms and processed chains"):
    assertEquals(iri("UniProtKB:P12345-2"), "http://purl.uniprot.org/isoforms/P12345-2")
    assertEquals(iri("UniProtKB:P13423-PRO_0000021998"), "http://purl.uniprot.org/annotation/PRO_0000021998")

  test("follows aliases, including UniProtKB routing"):
    assertEquals(iri("SPTR:Q9Y468"), "http://purl.uniprot.org/uniprot/Q9Y468")
    assertEquals(iri("SPTR:Q9Y468-3"), "http://purl.uniprot.org/isoforms/Q9Y468-3")

  test("shortens a local part that repeats its prefix"):
    assertEquals(iri("MGI:MGI:97486"), "http://identifiers.org/mgi/97486")
    assertEquals(iri("HGNC:HGNC:5"), "http://identifiers.org/hgnc/5")
    assertEquals(iri("MGI:97486"), "http://identifiers.org/mgi/97486")

  test("strips versions where the table says so, through aliases"):
    assertEquals(iri("Ensembl:ENSG00000173598.15"), "http://identifiers.org/ensembl/ENSG00000173598")
    assertEquals(iri("ENSEMBL:ENSG00000173598"), "http://identifiers.org/ensembl/ENSG00000173598")

  test("accepts bases with a fragment"):
    assertEquals(iri("Reactome:R-HSA-123"), "http://www.reactome.org/PathwayBrowser/#/R-HSA-123")

  test("percent-encodes characters an IRI may not contain"):
    assertEquals(iri("DOI:10.1000/a b<c>"), "https://doi.org/10.1000/a%20b%3Cc%3E")
    assertEquals(iri("DOI:10.1000/x%2Fy"), "https://doi.org/10.1000/x%2Fy")

  test("reports dropped, unknown and malformed identifiers"):
    assertEquals(table.expand("GR:AT1G01010.1"), Expansion.Dropped("GR"))
    assertEquals(table.expand("FOO:1"), Expansion.UnknownPrefix("FOO"))
    assert(table.expand("has_regulation_target").isInstanceOf[Expansion.Invalid])
    assert(table.expand("UniProtKB:").isInstanceOf[Expansion.Invalid])
    assert(table.expand(":P12345").isInstanceOf[Expansion.Invalid])

  test("rejects duplicate prefixes, alias cycles and missing UniProtKB routes"):
    val base = Seq(
      ("UniProtKB", "http://purl.uniprot.org/uniprot/", "-"),
      ("UniProtKB.isoform", "http://purl.uniprot.org/isoforms/", "-"),
      ("UniProtKB.chain", "http://purl.uniprot.org/annotation/", "-"),
    )
    intercept[ConversionException](PrefixTable.fromRows(base :+ ("UniProtKB", "http://x/", "-")))
    intercept[ConversionException](PrefixTable.fromRows(base ++ Seq(("A", "=B", "-"), ("B", "=A", "-"))))
    intercept[ConversionException](PrefixTable.fromRows(base :+ ("A", "=Missing", "-")))
    intercept[ConversionException](PrefixTable.fromRows(base :+ ("A", "http://x/", "lowercase")))
    intercept[ConversionException](PrefixTable.fromRows(base.take(2)))

  test("strips listed text from the start of a local part, through aliases"):
    val withStrip = PrefixTable.fromRows(
      Seq(
        ("UniProtKB", "http://purl.uniprot.org/uniprot/", "-"),
        ("UniProtKB.isoform", "http://purl.uniprot.org/isoforms/", "-"),
        ("UniProtKB.chain", "http://purl.uniprot.org/annotation/", "-"),
        ("NCBITaxon", "http://purl.obolibrary.org/obo/NCBITaxon_", "strip=taxon:"),
        ("taxon", "=NCBITaxon", "-"),
      )
    )
    val aureus = Expansion.Iri("http://purl.obolibrary.org/obo/NCBITaxon_1280")
    assertEquals(withStrip.expand("NCBITaxon:taxon:1280"), aureus)
    assertEquals(withStrip.expand("NCBITaxon:1280"), aureus)
    assertEquals(withStrip.expand("taxon:1280"), aureus)
    intercept[ConversionException](
      PrefixTable.fromRows(
        Seq(
          ("UniProtKB", "http://purl.uniprot.org/uniprot/", "-"),
          ("UniProtKB.isoform", "http://purl.uniprot.org/isoforms/", "-"),
          ("UniProtKB.chain", "http://purl.uniprot.org/annotation/", "-"),
          ("A", "http://x/", "strip="),
        )
      )
    )

  test("refuses a ':' left in the local part under an OBO-style base"):
    val obo = PrefixTable.fromRows(
      Seq(
        ("UniProtKB", "http://purl.uniprot.org/uniprot/", "-"),
        ("UniProtKB.isoform", "http://purl.uniprot.org/isoforms/", "-"),
        ("UniProtKB.chain", "http://purl.uniprot.org/annotation/", "-"),
        ("NCBITaxon", "http://purl.obolibrary.org/obo/NCBITaxon_", "-"),
        ("DOI", "https://doi.org/", "-"),
      )
    )
    assert(obo.expand("NCBITaxon:taxon:1280").isInstanceOf[Expansion.Invalid])
    assertEquals(obo.expand("DOI:10.1000/a:b"), Expansion.Iri("https://doi.org/10.1000/a:b"))

  test("the shipped prefix table loads"):
    val shipped = PrefixTable.load(Path.of("prefixes.tsv"))
    assertEquals(shipped.expand("GO:0005515"), Expansion.Iri("http://purl.obolibrary.org/obo/GO_0005515"))
    assertEquals(shipped.expand("GO_REF:0000033"), Expansion.Iri("http://purl.obolibrary.org/obo/go/references/0000033"))
    assertEquals(shipped.expand("gomodel:SYNGO_1980"), Expansion.Iri("http://model.geneontology.org/SYNGO_1980"))
