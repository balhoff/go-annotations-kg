package org.renci.goannotationkg

import org.apache.jena.atlas.json.{JSON, JsonArray, JsonObject}
import org.apache.jena.graph.{Node, NodeFactory}
import org.apache.jena.riot.{Lang, RDFDataMgr, RDFLanguages}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** A GO group, the value of GPAD's Assigned_By. name is the longer name, when it differs. */
final case class Group(iri: String, shorthand: String, name: Option[String])

/** An ECO release: its GO evidence codes, and the version its owl:versionIRI names. */
final case class EcoRelease(codes: Map[String, Seq[String]], versionIri: Option[String]):
  /** "2026-07-10" from ".../eco/releases/2026-07-10/eco.owl". */
  def version: Option[String] = versionIri.flatMap(Labels.versionOf)

/** What GPAD values are resolved against: GO's groups, and ECO's GO evidence codes. */
final case class ReleaseMetadata(
    /** By Assigned_By value. */
    groups: Map[String, Group] = Map.empty,
    /** ECO CURIE to the GO evidence codes ECO gives it with ECO:9000002. */
    ecoCodes: Map[String, Seq[String]] = Map.empty,
):
  def hasEco: Boolean = ecoCodes.nonEmpty

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

  private val hasGoEvidenceCode = NodeFactory.createURI("http://purl.obolibrary.org/obo/ECO_9000002")
  private val versionIri = NodeFactory.createURI("http://www.w3.org/2002/07/owl#versionIRI")
  private val owlOntology = NodeFactory.createURI("http://www.w3.org/2002/07/owl#Ontology")
  private val ecoPrefix = "http://purl.obolibrary.org/obo/ECO_"

  /** ECO:9000002 ("has GO evidence code") assertions and the version IRI, from ECO's OWL release.
    * The syntax comes from the file name (eco.owl is RDF/XML); OBO format is not read.
    */
  def loadEco(path: Path): EcoRelease =
    val lang = Option(RDFLanguages.filenameToLang(path.toString)).getOrElse(Lang.RDFXML)
    val graph =
      try RDFDataMgr.loadGraph(path.toString, lang)
      catch case error: Exception => throw ConversionException(s"could not read ECO '$path': ${error.getMessage}", error)
    val codes = graph.find(Node.ANY, hasGoEvidenceCode, Node.ANY).asScala.toVector
      .filter(t => t.getSubject.isURI && t.getSubject.getURI.startsWith(ecoPrefix) && t.getObject.isLiteral)
      .groupMap(t => "ECO:" + t.getSubject.getURI.stripPrefix(ecoPrefix))(_.getObject.getLiteralLexicalForm)
      .view.mapValues(_.distinct.sorted).toMap
    if codes.isEmpty then throw ConversionException(s"'$path' has no ECO:9000002 assertions; is it ECO's eco.owl?")
    val version = graph.find(Node.ANY, Vocab.Rdf.`type`, owlOntology).asScala.toVector
      .flatMap(t => graph.find(t.getSubject, versionIri, Node.ANY).asScala.map(_.getObject))
      .collectFirst { case n if n.isURI => n.getURI }
    EcoRelease(codes, version)

  /** The DOI GO's release-archive-doi.json gives for the release's Zenodo archive, as a doi.org IRI. */
  def loadReleaseDoi(path: Path): String =
    val json =
      try JSON.parseAny(Files.newInputStream(path))
      catch case error: Exception => throw ConversionException(s"could not read '$path': ${error.getMessage}", error)
    val doi = json match
      case obj: JsonObject if obj.hasKey("doi") && obj.get("doi").isString => obj.get("doi").getAsString.value().trim
      case _ => throw ConversionException(s"'$path' has no \"doi\" string")
    PrefixTable.checkIri("https://doi.org/" + doi) match
      case Expansion.Iri(iri) => iri
      case _ => throw ConversionException(s"'$path' gives an unusable DOI: '$doi'")
