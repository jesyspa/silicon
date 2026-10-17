package viper.silicon.decider

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters._

/** Wall time of prover queries, keyed by the symbolic-execution code that issued them.
  * Enabled with -Dsilicon.queryProfile=true; `drain` returns and resets the totals.
  */
object QueryProfile {
  val enabled: Boolean = java.lang.Boolean.getBoolean("silicon.queryProfile")
  private val depth = Integer.getInteger("silicon.queryProfileDepth", 3)

  final class Totals { val count = new LongAdder; val nanos = new LongAdder }
  private val totals = new ConcurrentHashMap[String, Totals]()

  private val skipped = Seq("viper.silicon.decider.", "scala.", "java.", "jdk.", "viper.silicon.utils.")

  private def site(): String = {
    val frames = Thread.currentThread.getStackTrace.iterator
      .map(f => (f.getClassName, f.getMethodName))
      .filter { case (c, _) => c.startsWith("viper.silicon.") && !skipped.exists(c.startsWith) }
      .map { case (c, m) =>
        val cls = c.stripPrefix("viper.silicon.").takeWhile(_ != '$')
        val method = m.stripPrefix("$anonfun$").takeWhile(_ != '$')
        s"$cls.$method"
      }
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    while (frames.hasNext && out.size < depth) {
      val f = frames.next()
      if (out.isEmpty || out.last != f) out += f
    }
    out.mkString(" < ")
  }

  def time[T](kind: String, outcome: T => String)(body: => T): T = {
    if (!enabled) return body
    val start = System.nanoTime()
    val result = body
    val elapsed = System.nanoTime() - start
    val t = totals.computeIfAbsent(s"$kind\t${outcome(result)}\t${site()}", _ => new Totals)
    t.count.increment(); t.nanos.add(elapsed)
    result
  }

  /** Lines of `kind \t outcome \t site \t count \t nanos`. */
  def drain(): Seq[String] = {
    val keys = totals.keys.asScala.toSeq
    keys.flatMap { k =>
      Option(totals.remove(k)).map(t => s"$k\t${t.count.sum}\t${t.nanos.sum}")
    }
  }
}
