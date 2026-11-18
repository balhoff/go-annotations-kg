package org.renci.goannotationkg

import scala.collection.mutable

final class ConversionException(message: String, cause: Throwable | Null = null)
    extends RuntimeException(message, cause)

/** Named counters, reported at the end of a run. */
final class Stats:
  private val counts = mutable.TreeMap.empty[String, Long]
  private val examples = mutable.HashMap.empty[String, String]

  def add(key: String, n: Long = 1): Unit = counts.updateWith(key)(c => Some(c.getOrElse(0L) + n))

  /** Count a problem, remembering the first example of it. */
  def problem(key: String, example: => String): Unit =
    add(key)
    if !examples.contains(key) then examples(key) = example

  def apply(key: String): Long = counts.getOrElse(key, 0L)

  def problems: Seq[(String, Long)] = counts.toSeq.filter(_._1.startsWith("dropped."))

  /** One "key<TAB>count[<TAB>example]" line per counter, sorted by key. */
  def report: String =
    counts.map { (key, n) =>
      examples.get(key).fold(s"$key\t$n")(ex => s"$key\t$n\t$ex")
    }.mkString("", "\n", "\n")
