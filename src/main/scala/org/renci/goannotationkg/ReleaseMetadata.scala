package org.renci.goannotationkg

import org.apache.jena.atlas.json.{JSON, JsonArray, JsonObject}
import org.apache.jena.graph.{Node, NodeFactory}
import org.apache.jena.riot.RDFDataMgr

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** A GO group, the value of GPAD's Assigned_By. name is the longer name, when it differs. */
final case class Group(iri: String, shorthand: String, name: Option[String])

/** What GPAD values are resolved against: GO's groups, and ECO's GO evidence codes. */
final case class ReleaseMetadata(
    /** By Assigned_By value. */
    groups: Map[String, Group] = Map.empty,
    /** ECO CURIE to the GO evidence codes ECO gives it with ECO:9000002. */
    ecoCodes: Map[String, Seq[String]] = Map.empty,
    /** ECO's data-version, when ECO was loaded. */
    ecoVersion: Option[String] = None,
):
  def hasEco: Boolean = ecoVersion.isDefined || ecoCodes.nonEmpty

object ReleaseMetadata:
  private val shorthand = NodeFactory.createURI("http://www.geneontology.org/formats/oboInOwl#shorthand")

  /** Groups from GO's groups.ttl; db-xrefs.json's homepage stands in for a database groups.ttl lacks. */
  def loadGroups(groupsTtl: Option[Path], dbXrefsJson: Option[Path]): Map[String, Group] =
    val fromGroups = groupsTtl.map { path =>
      val graph =
        try RDFDataMgr.loadGraph(path.toString)
        catch case error: Exception => throw ConversionException(s"could not read groups '$path': ${error.getMessage}", error)
      graph.find(Node.ANY, shorthand, Node.ANY).asScala.toVector
        .filter(t => t.getSubject.isURI && t.getObject.isLiteral)
        .sortBy(_.getSubject.getURI)
        .map { t =>
          val abbreviation = t.getObject.getLiteralLexicalForm
          val label = graph.find(t.getSubject, Vocab.Rdfs.label, Node.ANY).asScala.toVector
            .map(_.getObject).filter(_.isLiteral).map(_.getLiteralLexicalForm).sorted.headOption
          abbreviation -> Group(t.getSubject.getURI, abbreviation, label.filter(_ != abbreviation))
        }
        .distinctBy(_._1)
        .toMap
    }.getOrElse(Map.empty)

    val fromDbXrefs = dbXrefsJson.map { path =>
      val json =
        try JSON.parseAny(Files.newInputStream(path))
        catch case error: Exception => throw ConversionException(s"could not read db-xrefs '$path': ${error.getMessage}", error)
      if !json.isArray then throw ConversionException(s"db-xrefs '$path' must be a JSON array")
      json.getAsArray.asScala.toVector.collect {
        case entry: JsonObject if entry.hasKey("database") && entry.hasKey("generic_urls") =>
          val database = entry.get("database").getAsString.value()
          val urls = entry.get("generic_urls") match
            case array: JsonArray => array.asScala.toVector.filter(_.isString).map(_.getAsString.value())
            case _ => Vector.empty
          urls.headOption.flatMap { url =>
            PrefixTable.checkIri(url) match
              case Expansion.Iri(iri) => Some(database -> Group(iri, database, None))
              case _ => None
          }
      }.flatten.distinctBy(_._1).toMap
    }.getOrElse(Map.empty)

    fromDbXrefs ++ fromGroups

  /** ECO:9000002 ("has GO evidence code") values from eco.obo, and its data-version. */
  def loadEco(ecoObo: Path): (Map[String, Seq[String]], Option[String]) =
    val codes = Map.newBuilder[String, Seq[String]]
    var version: Option[String] = None
    var inTerm = false
    var id = ""
    var current = Vector.empty[String]
    def flush(): Unit =
      if inTerm && id.nonEmpty && current.nonEmpty then codes += id -> current
      id = ""
      current = Vector.empty
    val code = """property_value: ECO:9000002 "([^"]+)".*""".r
    Files.readAllLines(ecoObo, UTF_8).asScala.foreach { line =>
      if line.startsWith("[") then
        flush()
        inTerm = line.trim == "[Term]"
      else if line.startsWith("data-version:") && version.isEmpty && !inTerm && id.isEmpty then
        version = Some(line.stripPrefix("data-version:").trim)
      else if inTerm && line.startsWith("id: ") then id = line.stripPrefix("id: ").trim
      else if inTerm then line match
        case code(value) => current = current :+ value
        case _ => ()
    }
    flush()
    val result = codes.result()
    if result.isEmpty then throw ConversionException(s"'$ecoObo' has no ECO:9000002 values; is it eco.obo?")
    (result, version)
