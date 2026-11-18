package org.renci.goannotationkg

import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.apache.jena.riot.system.StreamRDF

import java.io.InputStream
import scala.collection.mutable

import Vocab.*

/** Where a source file came from, for its provenance node. */
final case class Source(stream: InputStream, iri: Option[String])

/** Converts one species' GPAD file, and optionally its GPI file, to RDF 1.2.
  *
  * Each GPAD line becomes one annotation node (a blank node) that reifies the
  * gene product → GO term triple. Positive annotations (goann:Annotation) also
  * assert that triple; NOT annotations (goann:NegatedAnnotation) only reify it.
  * Gene products that the GPAD annotates are described from the GPI. See
  * docs/model.md.
  */
final class Converter(prefixes: PrefixTable, out: StreamRDF, val stats: Stats):
  private val speciesCode = "[A-Za-z0-9]+".r
  private val rnacentralId = """RNAcentral:URS[0-9A-Fa-f]+_(\d+)""".r
  private val uniprotDerived = """(?:UniProtKB|SPTR):([A-Z0-9]+)-.+""".r
  private val goEvidence = """go_evidence:([A-Za-z]+)""".r

  private var assertedSubject = ""
  private val assertedForSubject = mutable.HashSet.empty[(String, String)]

  def convert(species: String, gpad: Source, gpi: Option[Source]): Unit =
    if !speciesCode.matches(species) then
      throw ConversionException(s"species code must be alphanumeric, got '$species'")
    val subjects = mutable.LinkedHashSet.empty[String]

    val gpadReader = TabularReader(gpad.stream)
    checkVersion(gpadReader.headers, "gpad-version", "GPAD")
    gpad.iri.foreach(sourceFile(_, gpadReader.headers))
    gpadReader.foreachRow { (line, cols) =>
      stats.add("gpad.lines")
      try
        val annotation = Formats.parseGpad(line, cols)
        if this.annotation(species, annotation) then subjects += annotation.objectId
      catch case error: LineException => stats.problem("dropped.gpad-line", s"line ${error.line}: ${error.getMessage}")
    }

    val taxonOf = mutable.HashMap.empty[String, String]
    gpi.foreach { source =>
      val reader = TabularReader(source.stream)
      checkVersion(reader.headers, "gpi-version", "GPI")
      source.iri.foreach(sourceFile(_, reader.headers))
      reader.foreachRow { (line, cols) =>
        stats.add("gpi.lines")
        try
          val entity = Formats.parseGpi(line, cols)
          taxonOf(entity.id) = entity.taxon
          if subjects.remove(entity.id) then this.entity(entity)
        catch case error: LineException => stats.problem("dropped.gpi-line", s"line ${error.line}: ${error.getMessage}")
      }
    }
    subjects.foreach(entityNotInGpi(_, taxonOf))

  // ---- annotations ---------------------------------------------------------

  /** Writes one annotation; false when its triple cannot be expressed. */
  private def annotation(species: String, a: GpadAnnotation): Boolean =
    (iri(a.objectId, "subject"), iri(a.relation, "relation"), iri(a.term, "term")) match
      case (Some(subject), Some(relation), Some(term)) =>
        val proposition = NodeFactory.createTripleTerm(subject, relation, term)
        val node = blank(s"${species}a${a.line}")
        // Both kinds reify the triple; the type says which. Only a positive line asserts it.
        if a.negated then
          emit(node, Rdf.`type`, GoAnn.NegatedAnnotation)
          stats.add("annotations.negated")
        else
          if a.objectId != assertedSubject then
            assertedSubject = a.objectId
            assertedForSubject.clear()
          if assertedForSubject.add((a.relation, a.term)) then
            emit(subject, relation, term)
            stats.add("asserted-triples")
          emit(node, Rdf.`type`, GoAnn.Annotation)
          stats.add("annotations.positive")
        emit(node, Rdf.reifies, proposition)

        iri(a.evidence, "evidence").foreach(emit(node, Ro.hasEvidence, _))
        iri(a.reference, "reference").foreach(emit(node, Dcterms.source, _))
        emit(node, Dcterms.date, dateLiteral(a.date))
        emit(node, GoAnn.assignedBy, literal(a.assignedBy))
        withFrom(node, a)
        a.interactingTaxa.flatMap(iri(_, "interacting-taxon")).foreach(emit(node, GoAnn.interactingTaxon, _))
        extensions(node, a)
        properties(node, a)
        true
      case _ =>
        stats.problem("dropped.annotation", s"line ${a.line}: ${a.objectId} ${a.relation} ${a.term}")
        false

  /** Every identifier gets goann:withFrom; a ','-group also gets a node listing its members. */
  private def withFrom(node: Node, a: GpadAnnotation): Unit =
    a.withFrom.zipWithIndex.foreach { (group, k) =>
      val ids = group.flatMap(iri(_, "with-from"))
      ids.foreach(emit(node, GoAnn.withFrom, _))
      if group.size > 1 && ids.size > 1 then
        val groupNode = blank(s"${node.getBlankNodeLabel}w$k")
        emit(node, GoAnn.withFromGroup, groupNode)
        ids.foreach(emit(groupNode, Rdfs.member, _))
    }

  /** Each '|' conjunction is an anonymous class; each REL(TARGET) an owl:someValuesFrom restriction on it. */
  private def extensions(node: Node, a: GpadAnnotation): Unit =
    a.extensions.zipWithIndex.foreach { (conjunction, k) =>
      val expressions = conjunction.flatMap { element =>
        Formats.parseExpression(element) match
          case None =>
            stats.problem("dropped.extension.malformed", s"line ${a.line}: $element")
            None
          case Some(RelationalExpression(relation, target)) =>
            for
              r <- iri(relation, "extension-relation")
              t <- iri(target, "extension-target")
            yield (r, t)
      }
      if expressions.isEmpty then stats.problem("dropped.extension.conjunction", s"line ${a.line}: ${conjunction.mkString(",")}")
      else
        val classNode = blank(s"${node.getBlankNodeLabel}x$k")
        emit(node, GoAnn.extension, classNode)
        stats.add("extensions")
        expressions.zipWithIndex.foreach { case ((relation, target), j) =>
          val restriction = blank(s"${classNode.getBlankNodeLabel}r$j")
          emit(classNode, Rdfs.subClassOf, restriction)
          emit(restriction, Owl.onProperty, relation)
          emit(restriction, Owl.someValuesFrom, target)
        }
    }

  private def properties(node: Node, a: GpadAnnotation): Unit =
    a.properties.foreach {
      case ("id", value) => emit(node, Dcterms.identifier, literal(value))
      case ("comment", goEvidence(code)) => emit(node, GoAnn.evidenceCode, literal(code))
      case ("comment", value) => emit(node, Rdfs.comment, literal(value))
      case ("contributor-id", value) => agent(value).foreach(emit(node, Dcterms.contributor, _))
      case ("reviewer-id", value) => agent(value).foreach(emit(node, GoAnn.reviewer, _))
      case ("creation-date", value) => date(value, a.line).foreach(emit(node, Dcterms.created, _))
      case ("modification-date", value) => date(value, a.line).foreach(emit(node, Dcterms.modified, _))
      case ("reviewed-date", value) => date(value, a.line).foreach(emit(node, GoAnn.reviewed, _))
      case ("noctua-model-id", value) => iri(value, "gocam-model").foreach(emit(node, GoAnn.gocamModel, _))
      case ("model-state", value) => emit(node, GoAnn.modelState, literal(value))
      case (key, value) => stats.problem(s"dropped.gpad-property.$key", s"line ${a.line}: $key=$value")
    }

  /** ORCIDs arrive both as https://orcid.org/... IRIs and as orcid:... CURIEs. */
  private def agent(value: String): Option[Node] =
    if value.startsWith("http://") || value.startsWith("https://") then
      PrefixTable.checkIri(value) match
        case Expansion.Iri(iri) => Some(uri(iri))
        case _ =>
          stats.problem("dropped.agent.invalid", value)
          None
    else iri(value, "agent")

  // ---- gene products -------------------------------------------------------

  private def entity(e: GpiEntity): Unit =
    iri(e.id, "entity").foreach { subject =>
      stats.add("entities")
      iri(e.entityType, "entity-type").foreach(emit(subject, Rdf.`type`, _))
      val label = if e.symbol.nonEmpty then e.symbol else e.name
      if label.nonEmpty then emit(subject, Rdfs.label, literal(label))
      if e.name.nonEmpty then emit(subject, GoAnn.fullName, literal(e.name))
      e.synonyms.distinct.filterNot(s => s == label || s == e.name).foreach(s => emit(subject, Skos.altLabel, literal(s)))
      iri(e.taxon, "taxon").foreach(emit(subject, Ro.inTaxon, _))
      e.encodedBy.flatMap(iri(_, "encoded-by")).foreach(emit(subject, Ro.geneProductOf, _))
      if e.canonicalId != e.id then iri(e.canonicalId, "canonical").foreach(emit(subject, GoAnn.canonicalObject, _))
      e.members.flatMap(iri(_, "complex-member")).foreach(emit(subject, Ro.hasComponent, _))
      e.xrefs.distinct.flatMap(iri(_, "xref")).distinct.foreach(emit(subject, GoAnn.xref, _))
      e.properties.foreach {
        case ("db_subset" | "db-subset", value) => emit(subject, GoAnn.dbSubset, literal(value))
        case (key, value) => stats.problem(s"dropped.gpi-property.$key", s"line ${e.line}: $key=$value")
      }
    }

  /** Annotated objects the GPI lacks: take what the identifier itself tells. */
  private def entityNotInGpi(id: String, taxonOf: collection.Map[String, String]): Unit =
    stats.problem("entities.not-in-gpi", id)
    iri(id, "entity").foreach { subject =>
      id match
        case rnacentralId(taxon) => iri(s"NCBITaxon:$taxon", "taxon").foreach(emit(subject, Ro.inTaxon, _))
        case uniprotDerived(parent) =>
          val parentId = s"UniProtKB:$parent"
          iri(parentId, "canonical").foreach(emit(subject, GoAnn.canonicalObject, _))
          taxonOf.get(parentId).flatMap(iri(_, "taxon")).foreach(emit(subject, Ro.inTaxon, _))
        case _ => ()
    }

  // ---- source files --------------------------------------------------------

  private def checkVersion(headers: Vector[(String, String)], key: String, format: String): Unit =
    headers.collectFirst { case (`key`, value) => value } match
      case Some(version) if version.startsWith("2.") => ()
      case Some(version) => throw ConversionException(s"unsupported $format version '$version'; expected 2.x")
      case None => throw ConversionException(s"missing '!$key' header; is this a $format 2.0 file?")

  private def sourceFile(sourceIri: String, headers: Vector[(String, String)]): Unit =
    val file = PrefixTable.checkIri(sourceIri) match
      case Expansion.Iri(value) => uri(value)
      case _ => throw ConversionException(s"source IRI is not a valid IRI: '$sourceIri'")
    emit(file, Rdf.`type`, GoAnn.SourceFile)
    headers.foreach {
      case ("gpad-version" | "gpi-version", value) => emit(file, GoAnn.formatVersion, literal(value))
      case ("generated-by", value) => emit(file, Dcterms.creator, literal(value))
      case ("date-generated", value) =>
        // Headers write "2026-07-28 20:25"; xsd:dateTime wants "2026-07-28T20:25:00".
        val normalized = value match
          case s"$day $hh:$mm" if !mm.contains(':') => s"${day}T$hh:$mm:00"
          case s"$day $time" => s"${day}T$time"
          case other => other
        if Formats.isDateOrDateTime(normalized) then emit(file, Dcterms.created, dateLiteral(normalized))
        else emit(file, Dcterms.created, literal(value))
      case ("go-version", value) =>
        PrefixTable.checkIri(value) match
          case Expansion.Iri(iri) => emit(file, GoAnn.goVersion, uri(iri))
          case _ => emit(file, GoAnn.goVersion, literal(value))
      case _ => ()
    }

  // ---- terms ---------------------------------------------------------------

  private def iri(curie: String, role: String): Option[Node] =
    prefixes.expand(curie) match
      case Expansion.Iri(value) => Some(uri(value))
      case Expansion.Dropped(prefix) =>
        stats.add(s"skipped.$role.$prefix")
        None
      case Expansion.UnknownPrefix(prefix) =>
        stats.problem(s"dropped.$role.unknown-prefix.$prefix", curie)
        None
      case Expansion.Invalid(reason) =>
        stats.problem(s"dropped.$role.invalid", reason)
        None

  private def date(value: String, line: Long): Option[Node] =
    if Formats.isDateOrDateTime(value) then Some(dateLiteral(value))
    else
      stats.problem("dropped.date.invalid", s"line $line: $value")
      None

  private def dateLiteral(value: String): Node =
    val datatype = if value.contains('T') then XSDDatatype.XSDdateTime else XSDDatatype.XSDdate
    NodeFactory.createLiteralDT(value, datatype)

  private def literal(value: String): Node = NodeFactory.createLiteralString(value)
  private def uri(value: String): Node = NodeFactory.createURI(value)
  private def blank(label: String): Node = NodeFactory.createBlankNode(label)

  private def emit(subject: Node, predicate: Node, obj: Node): Unit =
    out.triple(Triple.create(subject, predicate, obj))
    stats.add("triples")
