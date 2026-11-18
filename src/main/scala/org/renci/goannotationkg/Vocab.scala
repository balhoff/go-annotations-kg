package org.renci.goannotationkg

import org.apache.jena.graph.{Node, NodeFactory}

/** Every IRI the converter writes that does not come from the data. */
object Vocab:
  /** The KG's own vocabulary. Definitions are in vocab/goann.ttl. */
  val GoAnnNamespace = "https://w3id.org/go-annotation-kg/vocab/"

  private def iri(value: String): Node = NodeFactory.createURI(value)
  private def goann(local: String): Node = iri(GoAnnNamespace + local)
  private def obo(local: String): Node = iri("http://purl.obolibrary.org/obo/" + local)
  private def dcterms(local: String): Node = iri("http://purl.org/dc/terms/" + local)

  object Rdf:
    val `type`: Node = iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    val reifies: Node = iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies")

  object Rdfs:
    val label: Node = iri("http://www.w3.org/2000/01/rdf-schema#label")
    val comment: Node = iri("http://www.w3.org/2000/01/rdf-schema#comment")
    val subClassOf: Node = iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    val member: Node = iri("http://www.w3.org/2000/01/rdf-schema#member")

  object Owl:
    val onProperty: Node = iri("http://www.w3.org/2002/07/owl#onProperty")
    val someValuesFrom: Node = iri("http://www.w3.org/2002/07/owl#someValuesFrom")

  object Skos:
    val altLabel: Node = iri("http://www.w3.org/2004/02/skos/core#altLabel")

  object Dcterms:
    val source: Node = dcterms("source")
    val date: Node = dcterms("date")
    val created: Node = dcterms("created")
    val modified: Node = dcterms("modified")
    val identifier: Node = dcterms("identifier")
    val contributor: Node = dcterms("contributor")
    val creator: Node = dcterms("creator")

  object Ro:
    val hasEvidence: Node = obo("RO_0002558")
    val inTaxon: Node = obo("RO_0002162")
    val geneProductOf: Node = obo("RO_0002204")
    val hasComponent: Node = obo("RO_0002180")

  object GoAnn:
    val Annotation: Node = goann("Annotation")
    val NegatedAnnotation: Node = goann("NegatedAnnotation")
    val SourceFile: Node = goann("SourceFile")
    val negates: Node = goann("negates")
    val evidenceCode: Node = goann("evidenceCode")
    val withFrom: Node = goann("withFrom")
    val withFromGroup: Node = goann("withFromGroup")
    val interactingTaxon: Node = goann("interactingTaxon")
    val assignedBy: Node = goann("assignedBy")
    val extension: Node = goann("extension")
    val gocamModel: Node = goann("gocamModel")
    val modelState: Node = goann("modelState")
    val reviewer: Node = goann("reviewer")
    val reviewed: Node = goann("reviewed")
    val fullName: Node = goann("fullName")
    val dbSubset: Node = goann("dbSubset")
    val canonicalObject: Node = goann("canonicalObject")
    val xref: Node = goann("xref")
    val formatVersion: Node = goann("formatVersion")
    val goVersion: Node = goann("goVersion")
