package org.renci.goannotationkg

import org.apache.jena.graph.{Node, NodeFactory}

/** Every IRI the converter writes that does not come from the data. */
object Vocab:
  /** The KG's own vocabulary, for what no existing vocabulary covers. Definitions are in vocab/goann.ttl. */
  val GoAnnNamespace = "https://w3id.org/go-annotation-kg/vocab/"

  /** The GPAD/GPI 2.0 specification, which every accepted 2.x file conforms to. */
  val GpadGpiSpec = "https://github.com/geneontology/go-annotation/blob/master/specs/gpad-gpi-2-0.md"

  private def iri(value: String): Node = NodeFactory.createURI(value)
  private def goann(local: String): Node = iri(GoAnnNamespace + local)
  private def obo(local: String): Node = iri("http://purl.obolibrary.org/obo/" + local)
  private def dcterms(local: String): Node = iri("http://purl.org/dc/terms/" + local)
  private def pav(local: String): Node = iri("http://purl.org/pav/" + local)

  object Rdf:
    val `type`: Node = iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    val reifies: Node = iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies")

  object Rdfs:
    val label: Node = iri("http://www.w3.org/2000/01/rdf-schema#label")
    val comment: Node = iri("http://www.w3.org/2000/01/rdf-schema#comment")
    val subClassOf: Node = iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    val member: Node = iri("http://www.w3.org/2000/01/rdf-schema#member")
    val seeAlso: Node = iri("http://www.w3.org/2000/01/rdf-schema#seeAlso")

  object Owl:
    val onProperty: Node = iri("http://www.w3.org/2002/07/owl#onProperty")
    val someValuesFrom: Node = iri("http://www.w3.org/2002/07/owl#someValuesFrom")

  object OboInOwl:
    val hasExactSynonym: Node = iri("http://www.geneontology.org/formats/oboInOwl#hasExactSynonym")
    val hasRelatedSynonym: Node = iri("http://www.geneontology.org/formats/oboInOwl#hasRelatedSynonym")

  object Dcterms:
    val source: Node = dcterms("source")
    val date: Node = dcterms("date")
    val created: Node = dcterms("created")
    val modified: Node = dcterms("modified")
    val identifier: Node = dcterms("identifier")
    val contributor: Node = dcterms("contributor")
    val creator: Node = dcterms("creator")
    val conformsTo: Node = dcterms("conformsTo")

  object Pav:
    val providedBy: Node = pav("providedBy")
    val curatedBy: Node = pav("curatedBy")
    val curatedOn: Node = pav("curatedOn")
    val version: Node = pav("version")

  object Prov:
    val wasDerivedFrom: Node = iri("http://www.w3.org/ns/prov#wasDerivedFrom")

  /** GO-CAM's annotation vocabulary, as Minerva reads and writes it. */
  object Lego:
    val evidenceWith: Node = iri("http://geneontology.org/lego/evidence-with")
    val modelstate: Node = iri("http://geneontology.org/lego/modelstate")

  object UniProt:
    val reviewed: Node = iri("http://purl.uniprot.org/core/reviewed")

  object Ro:
    val hasEvidence: Node = obo("RO_0002558")
    val inTaxon: Node = obo("RO_0002162")
    val geneProductOf: Node = obo("RO_0002204")
    val hasComponent: Node = obo("RO_0002180")
    val hasInput: Node = obo("RO_0002233")

  object Eco:
    /** ECO's own "has GO evidence code" annotation property, on ECO classes. */
    val hasGoEvidenceCode: Node = obo("ECO_9000002")

  object GoAnn:
    val Annotation: Node = goann("Annotation")
    val NegatedAnnotation: Node = goann("NegatedAnnotation")
    val SourceFile: Node = goann("SourceFile")
    val extension: Node = goann("extension")
    val withFromGroup: Node = goann("withFromGroup")
    val canonicalObject: Node = goann("canonicalObject")
    val goVersion: Node = goann("goVersion")
