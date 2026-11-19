package org.renci.goannotationkg

import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.apache.jena.riot.{Lang, RDFFormat, RDFLanguages, RDFParser}
import org.apache.jena.riot.system.{StreamRDFBase, StreamRDFWriter}
import org.yaml.snakeyaml.Yaml
import scopt.OParser

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import Vocab.*

/** One row of ontologies.tsv: an OWL file and the namespaces it is authoritative for. */
final case class OntologySource(namespaces: Seq[String], location: String)

/** The labels step: an rdfs:label for each external term a converted release uses.
  *
  * The converter lists the OBO-namespace IRIs each output uses (--terms-output).
  * This reads those lists, streams each needed ontology's OWL release once, and
  * keeps only the rdfs:label triples of used terms from the term's own ontology.
  * GO_REF titles come from GO's gorefs.yaml. Each ontology read is recorded as a
  * source file, by its owl:versionIRI, and as a source the dataset derives from.
  */
object Labels:
  final case class Config(
      terms: Seq[String] = Nil,
      ontologies: String = "",
      releaseUrl: Option[String] = None,
      overrides: Map[String, String] = Map.empty,
      gorefs: Option[String] = None,
      gorefsSource: Option[String] = None,
      datasetIri: Option[String] = None,
      output: String = "-",
      report: Option[String] = None,
      unlabelled: Option[String] = None,
  )

  private val obo = "http://purl.obolibrary.org/obo/"
  private val goRefBase = obo + "go/references/"
  private val termIri = """http://purl\.obolibrary\.org/obo/([A-Za-z]+)_[^/#]+""".r
  private val labelIri = "http://www.w3.org/2000/01/rdf-schema#label"
  private val versionIriIri = "http://www.w3.org/2002/07/owl#versionIRI"
  private val altIdIri = "http://www.geneontology.org/formats/oboInOwl#hasAlternativeId"

  /** "GO" for an OBO term IRI, "GO_REF" for a GO reference; None for anything else. */
  def namespaceOf(iri: String): Option[String] = iri match
    case termIri(namespace) => Some(namespace)
    case _ if iri.startsWith(goRefBase) => Some("GO_REF")
    case _ => None

  /** "2026-07-10" from ".../eco/releases/2026-07-10/eco.owl": the segment before the file name. */
  def versionOf(versionIri: String): Option[String] =
    versionIri.split('/').toSeq.dropRight(1).lastOption.filter(_.nonEmpty)

  private val builder = OParser.builder[Config]
  private val parser =
    import builder.*
    OParser.sequence(
      programName("go-annotations-kg-labels"),
      head("go-annotations-kg labels", "0.1.0"),
      help("help").abbr("h").text("show this help message"),
      opt[String]("ontologies")
        .required()
        .valueName("<ontologies.tsv>")
        .action((value, config) => config.copy(ontologies = value))
        .text("where each namespace's labels come from"),
      opt[String]("release-url")
        .valueName("<URL>")
        .action((value, config) => config.copy(releaseUrl = Some(value)))
        .text("the GO release URL, substituted for {release} in ontologies.tsv"),
      opt[String]("override")
        .unbounded()
        .valueName("<NAMESPACE=PATH>")
        .action { (value, config) =>
          val (namespace, path) = value.span(_ != '=')
          config.copy(overrides = config.overrides + (namespace -> path.drop(1)))
        }
        .text("read this file instead for a namespace's source, e.g. ECO=eco.owl"),
      opt[String]("gorefs")
        .valueName("<gorefs.yaml>")
        .action((value, config) => config.copy(gorefs = Some(value)))
        .text("GO's gorefs.yaml, for GO_REF titles"),
      opt[String]("gorefs-source")
        .valueName("<IRI>")
        .action((value, config) => config.copy(gorefsSource = Some(value)))
        .text("IRI of the published gorefs.yaml, for its provenance node"),
      opt[String]("dataset-iri")
        .valueName("<IRI>")
        .action((value, config) => config.copy(datasetIri = Some(value)))
        .text("the KG's dataset IRI, which is derived from each ontology read"),
      opt[String]('o', "output")
        .valueName("<labels.nt[.gz]>")
        .action((value, config) => config.copy(output = value))
        .text("N-Triples output, or - for stdout (default: -)"),
      opt[String]("report")
        .valueName("<report.tsv>")
        .action((value, config) => config.copy(report = Some(value)))
        .text("write every counter, with an example of each problem, as TSV"),
      opt[String]("unlabelled")
        .valueName("<unlabelled.tsv>")
        .action((value, config) => config.copy(unlabelled = Some(value)))
        .text("write every used term no source labelled, with a note, as TSV"),
      arg[String]("<terms.txt>...")
        .unbounded()
        .action((value, config) => config.copy(terms = config.terms :+ value))
        .text("term lists the converter wrote with --terms-output"),
    )

  def main(args: Array[String]): Unit =
    val exitCode = OParser.parse(parser, args, Config()) match
      case None => if args.exists(a => a == "--help" || a == "-h") then 0 else 2
      case Some(config) =>
        try
          run(config)
          0
        catch
          case NonFatal(error) =>
            System.err.println(s"go-annotations-kg labels: ${error.getMessage}")
            1
    if exitCode != 0 then sys.exit(exitCode)

  def loadSources(path: Path, releaseUrl: Option[String], overrides: Map[String, String]): Seq[OntologySource] =
    val rows = Files.readAllLines(path, UTF_8).asScala.toVector
      .filter(l => l.trim.nonEmpty && !l.startsWith("#"))
    if rows.isEmpty || !rows.head.startsWith("namespaces\tsource") then
      throw ConversionException(s"$path: expected a header 'namespaces<TAB>source<TAB>note'")
    val sources = rows.tail.map { line =>
      val cols = line.split("\t", -1)
      if cols.length < 2 then throw ConversionException(s"$path: expected namespaces and a source in '$line'")
      val namespaces = cols(0).split(",").map(_.trim).filter(_.nonEmpty).toSeq
      val overridden = namespaces.flatMap(overrides.get).distinct
      val location = overridden match
        case Seq(file) => file
        case Seq() =>
          if cols(1).contains("{release}") then
            cols(1).replace("{release}", releaseUrl.getOrElse(throw ConversionException(s"$path: '${cols(1)}' needs --release-url")))
          else cols(1)
        case _ => throw ConversionException(s"conflicting --override files for ${namespaces.mkString(",")}")
      OntologySource(namespaces, location)
    }
    val repeated = sources.flatMap(_.namespaces).groupBy(identity).collect { case (n, s) if s.size > 1 => n }
    if repeated.nonEmpty then throw ConversionException(s"$path: ${repeated.mkString(", ")} listed more than once")
    sources

  def run(config: Config): Stats =
    val stats = Stats()
    val used = config.terms.flatMap(t => Files.readAllLines(Path.of(t), UTF_8).asScala.map(_.trim).filter(_.nonEmpty)).toSet
    val byNamespace = used.toSeq.flatMap(i => namespaceOf(i).map(_ -> i)).groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    val sources = loadSources(Path.of(config.ontologies), config.releaseUrl, config.overrides)
    val labels = mutable.TreeMap.empty[String, mutable.LinkedHashSet[Node]]
    val provenance = mutable.ArrayBuffer.empty[(String, Option[String])]
    val unlabelled = mutable.TreeMap.empty[String, String]

    // A source of "-" marks namespaces left unlabelled on purpose.
    for source <- sources if source.location == "-"; namespace <- source.namespaces; terms <- byNamespace.get(namespace) do
      stats.add(s"skipped.$namespace", terms.size)

    for source <- sources if source.location != "-" && source.namespaces.exists(byNamespace.contains) do
      val wanted = source.namespaces.flatMap(byNamespace.getOrElse(_, Set.empty)).toSet
      val altIds = mutable.HashMap.empty[String, String]
      var versionIri: Option[String] = None
      val started = System.nanoTime
      val sink = new StreamRDFBase:
        override def triple(t: Triple): Unit =
          if t.getSubject.isURI then
            t.getPredicate.getURI match
              case `labelIri` if t.getObject.isLiteral && wanted(t.getSubject.getURI) =>
                labels.getOrElseUpdate(t.getSubject.getURI, mutable.LinkedHashSet.empty) += t.getObject
              case `versionIriIri` if versionIri.isEmpty && t.getObject.isURI =>
                versionIri = Some(t.getObject.getURI)
              case `altIdIri` if t.getObject.isLiteral =>
                val alt = obo + t.getObject.getLiteralLexicalForm.replace(':', '_')
                if wanted(alt) then altIds(alt) = t.getSubject.getURI
              case _ => ()
      val lang = Option(RDFLanguages.filenameToLang(source.location)).getOrElse(Lang.RDFXML)
      try RDFParser.source(source.location).lang(lang).parse(sink)
      catch case NonFatal(error) => throw ConversionException(s"could not read ${source.location}: ${error.getMessage}", error)
      val iri = versionIri.getOrElse(source.location)
      provenance += iri -> versionIri.flatMap(versionOf)
      stats.add(s"seconds.${source.namespaces.mkString(",")}", (System.nanoTime - started) / 1_000_000_000L)
      for namespace <- source.namespaces; terms <- byNamespace.get(namespace) do
        val missing = terms.filterNot(labels.contains)
        stats.add(s"used.$namespace", terms.size)
        stats.add(s"labelled.$namespace", terms.size - missing.size)
        missing.toSeq.sorted.foreach { term =>
          val note = altIds.get(term).fold("")(primary => s"an alt_id of $primary")
          stats.problem(s"unlabelled.$namespace", if note.isEmpty then term else s"$term ($note)")
          unlabelled(term) = if note.isEmpty then s"not labelled in ${source.location}" else note
        }

    // GO_REF titles, from GO's own reference metadata.
    for terms <- byNamespace.get("GO_REF") do
      stats.add("used.GO_REF", terms.size)
      config.gorefs match
        case None => terms.foreach(t => stats.problem("unlabelled.GO_REF", s"$t (no --gorefs)"))
        case Some(path) =>
          val titles = goRefTitles(Path.of(path))
          terms.toSeq.sorted.foreach { term =>
            titles.get(term.stripPrefix(goRefBase)) match
              case Some(title) =>
                labels.getOrElseUpdate(term, mutable.LinkedHashSet.empty) += NodeFactory.createLiteralString(title)
                stats.add("labelled.GO_REF")
              case None =>
                stats.problem("unlabelled.GO_REF", term)
                unlabelled(term) = s"no title in $path"
          }
          config.gorefsSource.foreach(iri => provenance += iri -> None)

    val coveredNamespaces = sources.flatMap(_.namespaces).toSet + "GO_REF"
    byNamespace.keys.filterNot(coveredNamespaces).toSeq.sorted.foreach { namespace =>
      stats.add(s"used.$namespace", byNamespace(namespace).size)
      stats.problem(s"unlabelled.$namespace", s"no source for $namespace in ${config.ontologies}")
      byNamespace(namespace).foreach(term => unlabelled(term) = s"no source for $namespace")
    }

    write(config, labels, provenance.toSeq, stats)
    config.report.foreach(path => Files.writeString(Path.of(path), stats.report, UTF_8))
    config.unlabelled.foreach { path =>
      Files.writeString(Path.of(path), unlabelled.map((term, note) => s"$term\t$note").mkString("", "\n", "\n"), UTF_8)
    }
    stats

  /** GO_REF local ids ("0000033") to titles, alt_ids included. */
  def goRefTitles(path: Path): Map[String, String] =
    val loaded =
      try new Yaml().load[Any](Files.newInputStream(path))
      catch case NonFatal(error) => throw ConversionException(s"could not read '$path': ${error.getMessage}", error)
    val entries = loaded match
      case list: java.util.List[?] => list.asScala.toSeq
      case _ => throw ConversionException(s"'$path' is not a YAML list")
    entries.flatMap {
      case entry: java.util.Map[?, ?] =>
        val fields = entry.asScala.map((k, v) => k.toString -> v).toMap
        (fields.get("id"), fields.get("title")) match
          case (Some(id: String), Some(title: String)) =>
            val alts = fields.get("alt_id") match
              case Some(list: java.util.List[?]) => list.asScala.map(_.toString).toSeq
              case _ => Nil
            (id +: alts).map(i => i.stripPrefix("GO_REF:") -> title.replaceAll("\\s+", " ").trim)
          case _ => Nil
      case _ => Nil
    }.toMap

  private def write(
      config: Config,
      labels: collection.Map[String, collection.Set[Node]],
      provenance: Seq[(String, Option[String])],
      stats: Stats,
  ): Unit =
    val output = Io.openOutput(config.output)
    val rdf = StreamRDFWriter.getWriterStream(output.stream, RDFFormat.NTRIPLES_UTF8)
    def emit(s: Node, p: Node, o: Node): Unit =
      rdf.triple(Triple.create(s, p, o))
      stats.add("triples")
    try
      rdf.start()
      for (term, values) <- labels; value <- values do emit(NodeFactory.createURI(term), Rdfs.label, value)
      for (iri, version) <- provenance.distinct do
        val source = NodeFactory.createURI(iri)
        emit(source, Rdf.`type`, GoAnn.SourceFile)
        version.foreach(v => emit(source, Pav.version, NodeFactory.createLiteralString(v)))
        config.datasetIri.foreach(d => emit(NodeFactory.createURI(d), Prov.wasDerivedFrom, source))
      rdf.finish()
      output.commit()
    catch
      case error: Throwable =>
        try output.abort()
        catch case abortError: Throwable => error.addSuppressed(abortError)
        throw error
