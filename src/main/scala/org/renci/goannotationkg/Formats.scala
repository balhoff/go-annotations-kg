package org.renci.goannotationkg

import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8

/** One GPAD 2.0 annotation line. Identifiers are kept as CURIEs. */
final case class GpadAnnotation(
    line: Long,
    objectId: String,
    negated: Boolean,
    relation: String,
    term: String,
    reference: String,
    evidence: String,
    /** '|'-separated entries, each a ','-separated group (usually of one). */
    withFrom: Vector[Vector[String]],
    interactingTaxa: Vector[String],
    date: String,
    assignedBy: String,
    /** '|'-separated conjunctions, each a ','-separated list of "REL(TARGET)". */
    extensions: Vector[Vector[String]],
    properties: Vector[(String, String)],
)

/** One GPI 2.0 entity line. */
final case class GpiEntity(
    line: Long,
    id: String,
    symbol: String,
    name: String,
    synonyms: Vector[String],
    entityType: String,
    taxon: String,
    encodedBy: Vector[String],
    canonicalId: String,
    members: Vector[String],
    xrefs: Vector[String],
    properties: Vector[(String, String)],
)

final case class RelationalExpression(relation: String, target: String)

final class LineException(val line: Long, message: String) extends RuntimeException(message)

/** Reads a GPAD or GPI file: '!' header lines first, then tab-separated rows. */
final class TabularReader(input: InputStream):
  private val reader = new BufferedReader(new InputStreamReader(input, UTF_8), 1 << 20)
  private var lineNumber = 0L
  private var pending: String = null

  /** "!key: value" header properties, in order. Reading them consumes the header. */
  lazy val headers: Vector[(String, String)] =
    val builder = Vector.newBuilder[(String, String)]
    var line = nextRaw()
    while line != null && line.startsWith("!") do
      val body = line.stripPrefix("!")
      if !body.startsWith("!") then
        val colon = body.indexOf(':')
        if colon > 0 then builder += body.substring(0, colon).trim -> body.substring(colon + 1).trim
      line = nextRaw()
    pending = line
    builder.result()

  /** Calls f with (line number, columns) for every data row. */
  def foreachRow(f: (Long, Array[String]) => Unit): Unit =
    headers
    var line = pending
    pending = null
    while line != null do
      if line.nonEmpty && !line.startsWith("!") then f(lineNumber, line.split("\t", -1))
      line = nextRaw()

  private def nextRaw(): String =
    val line = reader.readLine()
    if line != null then lineNumber += 1
    line

object Formats:
  private val datePattern = """\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})?)?""".r
  private val expressionPattern = """([^()\s]+)\(([^()\s]+)\)""".r

  def isDateOrDateTime(value: String): Boolean = datePattern.matches(value)

  def parseExpression(value: String): Option[RelationalExpression] = value match
    case expressionPattern(relation, target) => Some(RelationalExpression(relation, target))
    case _ => None

  private def split(value: String, separator: Char): Vector[String] =
    if value.isEmpty then Vector.empty
    else value.split(java.util.regex.Pattern.quote(separator.toString), -1).iterator.map(_.trim).filter(_.nonEmpty).toVector

  private def properties(value: String, line: Long): Vector[(String, String)] =
    split(value, '|').map { pair =>
      val eq = pair.indexOf('=')
      if eq <= 0 then throw LineException(line, s"property without '=': '$pair'")
      pair.substring(0, eq).trim -> pair.substring(eq + 1).trim
    }

  /** Columns 11 and 12 are optional at the end of a line; anything else must be present. */
  private def columns(cols: Array[String], expected: Int, minimum: Int, line: Long, what: String): Array[String] =
    if cols.length > expected then
      throw LineException(line, s"$what line has ${cols.length} columns, expected $expected")
    if cols.length < minimum then
      throw LineException(line, s"$what line has ${cols.length} columns, expected $expected")
    if cols.length == expected then cols else cols.padTo(expected, "")

  private def required(cols: Array[String], index: Int, name: String, line: Long): String =
    val value = cols(index).trim
    if value.isEmpty then throw LineException(line, s"empty $name (column ${index + 1})")
    value

  def parseGpad(line: Long, raw: Array[String]): GpadAnnotation =
    val cols = columns(raw, 12, 10, line, "GPAD")
    val negation = cols(1).trim
    if negation.nonEmpty && negation != "NOT" then
      throw LineException(line, s"negation column must be empty or 'NOT', got '$negation'")
    val date = required(cols, 8, "annotation date", line)
    if !isDateOrDateTime(date) then throw LineException(line, s"annotation date is not ISO 8601: '$date'")
    GpadAnnotation(
      line = line,
      objectId = required(cols, 0, "DB_Object_ID", line),
      negated = negation == "NOT",
      relation = required(cols, 2, "relation", line),
      term = required(cols, 3, "ontology class", line),
      reference = required(cols, 4, "reference", line),
      evidence = required(cols, 5, "evidence type", line),
      withFrom = split(cols(6).trim, '|').map(split(_, ',')).filter(_.nonEmpty),
      interactingTaxa = split(cols(7).trim, '|'),
      date = date,
      assignedBy = required(cols, 9, "assigned by", line),
      extensions = split(cols(10).trim, '|').map(split(_, ',')).filter(_.nonEmpty),
      properties = properties(cols(11).trim, line),
    )

  def parseGpi(line: Long, raw: Array[String]): GpiEntity =
    val cols = columns(raw, 11, 10, line, "GPI")
    val id = required(cols, 0, "DB_Object_ID", line)
    GpiEntity(
      line = line,
      id = id,
      symbol = cols(1).trim,
      name = cols(2).trim,
      synonyms = split(cols(3).trim, '|'),
      entityType = required(cols, 4, "DB_Object_Type", line),
      taxon = required(cols, 5, "DB_Object_Taxon", line),
      encodedBy = split(cols(6).trim, '|'),
      canonicalId = Option(cols(7).trim).filter(_.nonEmpty).getOrElse(id),
      members = split(cols(8).trim, '|'),
      xrefs = split(cols(9).trim, '|'),
      properties = properties(cols(10).trim, line),
    )
