package org.renci.goannotationkg

import org.apache.jena.irix.{IRIException, IRIx}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The outcome of turning one CURIE into an IRI. */
enum Expansion:
  case Iri(value: String)
  /** The prefix is listed with target "-": dropped on purpose. */
  case Dropped(prefix: String)
  /** The prefix is not in the table. */
  case UnknownPrefix(prefix: String)
  /** Not a CURIE, or the expansion is not a valid IRI. */
  case Invalid(reason: String)

/** One resolved row: aliases are followed, and their rules merged into the target's. */
final case class PrefixEntry(
    prefix: String,
    base: Option[String],
    stripVersion: Boolean,
    /** Literal text removed from the start of a local part, e.g. GAF's "taxon:". */
    stripPrefixes: Seq[String] = Nil,
)

/** Expands GPAD/GPI CURIEs with the curated table in prefixes.tsv. */
final class PrefixTable private (entries: Map[String, PrefixEntry]):
  private val isoform = """([A-Z0-9]+)-(\d+)""".r
  private val chain = """([A-Z0-9]+)-(PRO_\d+)""".r
  private val version = """(.+)\.\d+""".r

  def contains(prefix: String): Boolean = entries.contains(prefix)

  def expand(curie: String): Expansion =
    val colon = curie.indexOf(':')
    if colon <= 0 || colon == curie.length - 1 then Expansion.Invalid(s"not a CURIE: '$curie'")
    else
      val prefix = curie.substring(0, colon)
      entries.get(prefix) match
        case None => Expansion.UnknownPrefix(prefix)
        case Some(entry) => expandWith(entry, prefix, curie.substring(colon + 1))

  private def expandWith(entry: PrefixEntry, written: String, rawLocal: String): Expansion =
    entry.base match
      case None => Expansion.Dropped(written)
      case Some(base) =>
        // "MGI:MGI:97486" and "HGNC:HGNC:5" repeat their prefix inside the local part.
        val unbanana =
          Seq(written, entry.prefix).distinct
            .map(_ + ":")
            .find(rawLocal.startsWith)
            .map(p => rawLocal.substring(p.length))
            .getOrElse(rawLocal)
        val unprefixed =
          entry.stripPrefixes.find(unbanana.startsWith).map(p => unbanana.substring(p.length)).getOrElse(unbanana)
        val local =
          if entry.stripVersion then unprefixed match
            case version(unversioned) => unversioned
            case other => other
          else unprefixed
        if local.isEmpty then Expansion.Invalid(s"empty local identifier in '$written:$rawLocal'")
        // An OBO-style base ends in "_" and takes a bare local id; a ':' left in one
        // ("NCBITaxon:taxon:1280") would make a well-formed but wrong IRI.
        else if base.endsWith("_") && local.contains(':') then
          Expansion.Invalid(s"unexpected ':' in the local part of '$written:$rawLocal'")
        else
          val iri =
            if entry.prefix == "UniProtKB" then
              local match
                case isoform(_, _) => entries("UniProtKB.isoform").base.get + local
                case chain(_, pro) => entries("UniProtKB.chain").base.get + pro
                case _ => base + PrefixTable.encodeLocal(local)
            else base + PrefixTable.encodeLocal(local)
          PrefixTable.checkIri(iri)

object PrefixTable:
  def load(path: Path): PrefixTable =
    val lines = Files.readAllLines(path, UTF_8).asScala.toVector
    val rows = lines.zipWithIndex.collect {
      case (line, index) if line.trim.nonEmpty && !line.startsWith("#") => (line, index + 1)
    }
    if rows.isEmpty then throw ConversionException(s"$path: no rows")
    val header = rows.head._1.split("\t", -1).toVector
    if header.take(3) != Vector("prefix", "target", "rules") then
      throw ConversionException(s"$path: expected a header 'prefix<TAB>target<TAB>rules<TAB>basis'")
    val raw = rows.tail.map { (line, number) =>
      val cols = line.split("\t", -1)
      if cols.length < 3 then throw ConversionException(s"$path:$number: expected at least 3 columns")
      (cols(0), cols(1), cols(2), number)
    }
    fromRows(raw.map((p, t, r, _) => (p, t, r)), path.toString)

  /** Rows of (prefix, target, rules), as in the TSV. */
  def fromRows(rows: Seq[(String, String, String)], source: String = "prefix table"): PrefixTable =
    val byPrefix = rows.groupBy(_._1)
    byPrefix.collectFirst { case (prefix, dups) if dups.size > 1 => prefix }.foreach { prefix =>
      throw ConversionException(s"$source: prefix '$prefix' is listed more than once")
    }
    val targets = rows.map(row => row._1 -> row).toMap

    def resolve(prefix: String, seen: List[String]): PrefixEntry =
      if seen.contains(prefix) then
        throw ConversionException(s"$source: alias cycle ${(prefix :: seen).reverse.mkString(" -> ")}")
      targets.get(prefix) match
        case None => throw ConversionException(s"$source: alias target '$prefix' is not listed")
        case Some((_, target, rules)) =>
          val ruleList = if rules == "-" || rules.isEmpty then Seq.empty[String] else rules.split(",").map(_.trim).toSeq
          val stripVersion = ruleList.contains("strip_version")
          val stripPrefixes = ruleList.collect { case s"strip=$text" if text.nonEmpty => text }
          val unknownRules = ruleList.filterNot(r => r == "strip_version" || r.startsWith("strip="))
          if unknownRules.nonEmpty || ruleList.contains("strip=") then
            throw ConversionException(s"$source: unknown rules ${(unknownRules ++ ruleList.filter(_ == "strip=")).mkString(", ")} for '$prefix'")
          if target.startsWith("=") then
            val aliased = resolve(target.drop(1), prefix :: seen)
            aliased.copy(
              stripVersion = aliased.stripVersion || stripVersion,
              stripPrefixes = (stripPrefixes ++ aliased.stripPrefixes).distinct,
            )
          else if target == "-" then PrefixEntry(prefix, None, stripVersion = false)
          else
            checkIri(target + "x") match
              case Expansion.Iri(_) => PrefixEntry(prefix, Some(target), stripVersion, stripPrefixes)
              case _ => throw ConversionException(s"$source: '$target' for '$prefix' is not an IRI base")

    val entries = rows.map(row => row._1 -> resolve(row._1, Nil)).toMap
    for required <- Seq("UniProtKB", "UniProtKB.isoform", "UniProtKB.chain") do
      if !entries.get(required).exists(_.base.isDefined) then
        throw ConversionException(s"$source: '$required' must be listed with an IRI base")
    new PrefixTable(entries)

  // Characters N-Triples forbids in an IRIREF, plus ones IRIs forbid unescaped.
  private val mustEncode: Set[Char] = " <>\"{}|\\^`".toSet

  /** Percent-encode what an IRI may not contain; existing %HH escapes are kept. */
  private[goannotationkg] def encodeLocal(local: String): String =
    if !local.exists(c => mustEncode(c) || c < 0x20 || c == 0x7f) then local
    else
      val out = new StringBuilder(local.length + 8)
      local.foreach { c =>
        if mustEncode(c) || c < 0x20 || c == 0x7f then out.append(f"%%${c.toInt}%02X")
        else out.append(c)
      }
      out.toString

  private[goannotationkg] def checkIri(iri: String): Expansion =
    try
      // isAbsolute is RFC 3986's absolute-URI, which excludes fragments; we only need a scheme.
      val parsed = IRIx.create(iri)
      if parsed.isRelative then Expansion.Invalid(s"not an absolute IRI: <$iri>")
      else Expansion.Iri(iri)
    catch case error: IRIException => Expansion.Invalid(s"invalid IRI <$iri>: ${error.getMessage}")
