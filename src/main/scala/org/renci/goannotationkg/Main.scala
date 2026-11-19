package org.renci.goannotationkg

import org.apache.jena.riot.RDFFormat
import org.apache.jena.riot.system.StreamRDFWriter
import scopt.OParser

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

final case class Config(
    prefixes: String = "",
    gpad: String = "",
    gpi: Option[String] = None,
    species: Option[String] = None,
    gpadSource: Option[String] = None,
    gpiSource: Option[String] = None,
    groups: Option[String] = None,
    groupsSource: Option[String] = None,
    dbXrefs: Option[String] = None,
    dbXrefsSource: Option[String] = None,
    eco: Option[String] = None,
    ecoSource: Option[String] = None,
    goRelease: Option[String] = None,
    goReleaseDoi: Option[String] = None,
    datasetIri: Option[String] = None,
    converterVersion: Option[String] = None,
    output: String = "-",
    report: Option[String] = None,
    termsOutput: Option[String] = None,
    strict: Boolean = false,
    quiet: Boolean = false,
)

object Main:
  private val builder = OParser.builder[Config]
  private val parser =
    import builder.*
    OParser.sequence(
      programName("go-annotations-kg"),
      head("go-annotations-kg", "0.1.0"),
      help("help").abbr("h").text("show this help message"),
      version("version").text("show the version"),
      opt[String]("prefixes")
        .required()
        .valueName("<prefixes.tsv>")
        .action((value, config) => config.copy(prefixes = value))
        .text("prefix table (see prefixes.tsv)"),
      opt[String]("gpad")
        .required()
        .valueName("<SPECIES-uniprot.gpad[.gz]>")
        .action((value, config) => config.copy(gpad = value))
        .text("GPAD 2.0 file"),
      opt[String]("gpi")
        .valueName("<SPECIES-uniprot.gpi[.gz]>")
        .action((value, config) => config.copy(gpi = Some(value)))
        .text("GPI 2.0 file describing the GPAD's gene products"),
      opt[String]("species")
        .valueName("<CODE>")
        .action((value, config) => config.copy(species = Some(value)))
        .text("species code used in blank node labels (default: GPAD file name up to the first '-')"),
      opt[String]("gpad-source")
        .valueName("<IRI>")
        .action((value, config) => config.copy(gpadSource = Some(value)))
        .text("IRI of the published GPAD file, for its provenance node"),
      opt[String]("gpi-source")
        .valueName("<IRI>")
        .action((value, config) => config.copy(gpiSource = Some(value)))
        .text("IRI of the published GPI file, for its provenance node"),
      opt[String]("groups")
        .valueName("<groups.ttl>")
        .action((value, config) => config.copy(groups = Some(value)))
        .text("GO's groups.ttl, to give each Assigned_By value its group IRI"),
      opt[String]("groups-source")
        .valueName("<IRI>")
        .action((value, config) => config.copy(groupsSource = Some(value)))
        .text("IRI of the published groups.ttl, for its provenance node"),
      opt[String]("db-xrefs")
        .valueName("<db-xrefs.json>")
        .action((value, config) => config.copy(dbXrefs = Some(value)))
        .text("GO's db-xrefs.json, whose homepages stand in for groups groups.ttl lacks"),
      opt[String]("db-xrefs-source")
        .valueName("<IRI>")
        .action((value, config) => config.copy(dbXrefsSource = Some(value)))
        .text("IRI of the published db-xrefs.json, for its provenance node"),
      opt[String]("eco")
        .valueName("<eco.owl>")
        .action((value, config) => config.copy(eco = Some(value)))
        .text("ECO's OWL release, for the GO evidence code of each ECO class (ECO:9000002)"),
      opt[String]("eco-source")
        .valueName("<IRI>")
        .action((value, config) => config.copy(ecoSource = Some(value)))
        .text("IRI for the ECO file's provenance node when it has no owl:versionIRI"),
      opt[String]("go-release")
        .valueName("<YYYY-MM-DD>")
        .action((value, config) => config.copy(goRelease = Some(value)))
        .text("the GO release date, which is also the dataset's version"),
      opt[String]("go-release-doi")
        .valueName("<release-archive-doi.json>")
        .action((value, config) => config.copy(goReleaseDoi = Some(value)))
        .text("GO's file naming the release's Zenodo archive DOI, which identifies the release"),
      opt[String]("dataset-iri")
        .valueName("<IRI>")
        .action((value, config) => config.copy(datasetIri = Some(value)))
        .text("the KG's dataset IRI, for its provenance node"),
      opt[String]("converter-version")
        .valueName("<VERSION>")
        .action((value, config) => config.copy(converterVersion = Some(value)))
        .text("the converter's version, such as its git commit, recorded with pav:createdWith"),
      opt[String]('o', "output")
        .valueName("<output.nt[.gz]>")
        .action((value, config) => config.copy(output = value))
        .text("N-Triples 1.2 output, or - for stdout (default: -)"),
      opt[String]("terms-output")
        .valueName("<terms.txt>")
        .action((value, config) => config.copy(termsOutput = Some(value)))
        .text("write every OBO-namespace IRI the output uses, one per line, for the labels step"),
      opt[String]("report")
        .valueName("<report.tsv>")
        .action((value, config) => config.copy(report = Some(value)))
        .text("write every counter, with an example of each problem, as TSV"),
      opt[Unit]("strict")
        .action((_, config) => config.copy(strict = true))
        .text("fail, keeping no output, if any GPAD line or annotation is dropped"),
      opt[Unit]("quiet")
        .action((_, config) => config.copy(quiet = true))
        .text("do not print the summary"),
    )

  def main(args: Array[String]): Unit =
    val exitCode = OParser.parse(parser, args, Config()) match
      case None =>
        if args.exists(arg => arg == "--help" || arg == "-h" || arg == "--version") then 0 else 2
      case Some(config) =>
        try
          run(config)
          0
        catch
          case NonFatal(error) =>
            System.err.println(s"go-annotations-kg: ${error.getMessage}")
            1
    if exitCode != 0 then sys.exit(exitCode)

  def run(config: Config): Stats =
    val prefixes = PrefixTable.load(Path.of(config.prefixes))
    val species = config.species.getOrElse(Path.of(config.gpad).getFileName.toString.takeWhile(_ != '-'))
    val inputs = Seq(config.gpad) ++ config.gpi ++ config.groups ++ config.dbXrefs ++ config.eco ++ config.goReleaseDoi ++ Seq(config.prefixes)
    if config.output != "-" && inputs.exists(i => Path.of(i).toAbsolutePath.normalize() == Path.of(config.output).toAbsolutePath.normalize()) then
      throw ConversionException(s"output '${config.output}' must not be an input file")

    val groups = ReleaseMetadata.loadGroups(config.groups.map(Path.of(_)), config.dbXrefs.map(Path.of(_)))
    val eco = config.eco.map(e => ReleaseMetadata.loadEco(Path.of(e)))
    val metadata = ReleaseMetadata(groups, eco.map(_.codes).getOrElse(Map.empty))
    // ECO is named by the version IRI in the file itself, when it has one.
    val ecoSource = eco.flatMap(e => e.versionIri.orElse(config.ecoSource).map(MetadataSource(_, e.version)))
    val metadataSources =
      config.groupsSource.map(MetadataSource(_)).toSeq ++
        config.dbXrefsSource.map(MetadataSource(_)) ++
        ecoSource

    // The release is its Zenodo archive DOI where GO gives one, else its snapshot URL.
    val releaseIri = config.goReleaseDoi.map(p => ReleaseMetadata.loadReleaseDoi(Path.of(p)))
      .orElse(config.goRelease.map(r => s"https://release.geneontology.org/$r/"))
    val provenance = Provenance(
      datasetIri = config.datasetIri,
      releaseVersion = config.goRelease,
      releaseIri = releaseIri,
      releaseParts = Seq(config.gpadSource, config.gpiSource, config.groupsSource, config.dbXrefsSource).flatten,
      derivedFrom = releaseIri.toSeq ++ ecoSource.map(_.iri),
      converterVersion = config.converterVersion,
    )

    val output = Io.openOutput(config.output)
    val gpad = Io.openInput(config.gpad)
    val gpi = config.gpi.map(Io.openInput)
    val rdf = StreamRDFWriter.getWriterStream(output.stream, RDFFormat.NTRIPLES_UTF8)
    val converter = new Converter(prefixes, rdf, Stats(), metadata)
    try
      rdf.start()
      converter.convert(
        species,
        Source(gpad.stream, config.gpadSource),
        gpi.map(handle => Source(handle.stream, config.gpiSource)),
        metadataSources,
        provenance,
      )
      rdf.finish()
      val stats = converter.stats
      config.report.foreach(path => Files.writeString(Path.of(path), stats.report, UTF_8))
      config.termsOutput.foreach { path =>
        Files.writeString(Path.of(path), converter.usedTerms.toSeq.sorted.mkString("", "\n", "\n"), UTF_8)
      }
      val lost = stats("dropped.gpad-line") + stats("dropped.annotation")
      if config.strict && lost > 0 then
        throw ConversionException(s"$lost GPAD lines or annotations were dropped (--strict); see the report")
      output.commit()
      if !config.quiet then summarize(species, stats)
      stats
    catch
      case error: Throwable =>
        try output.abort()
        catch case abortError: Throwable => error.addSuppressed(abortError)
        throw error
    finally
      gpad.close()
      gpi.foreach(_.close())

  private def summarize(species: String, stats: Stats): Unit =
    val problems = stats.problems
    val problemText =
      if problems.isEmpty then ""
      else "; dropped " + problems.map((key, n) => s"$n ${key.stripPrefix("dropped.")}").mkString(", ")
    System.err.println(
      s"$species: ${stats("gpad.lines")} GPAD lines -> ${stats("annotations.positive")} annotations, " +
        s"${stats("annotations.negated")} NOT annotations, ${stats("entities")} gene products, " +
        s"${stats("triples")} triples$problemText"
    )
