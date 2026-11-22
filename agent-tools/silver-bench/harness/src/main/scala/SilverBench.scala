import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path, Paths}
import java.io.{FileWriter, PrintWriter}
import scala.jdk.CollectionConverters._
import ch.qos.logback.classic.{Level, Logger}
import org.slf4j.LoggerFactory
import viper.silver.ast.{Member, Method, Function, Predicate, Program}
import viper.silver.ast.utility.QuantifiedPermissions
import viper.silver.frontend.{SilFrontend, SilFrontendConfig}
import viper.silver.reporter.NoopReporter
import viper.silver.verifier.{Success, VerificationResult, Verifier}

/* Runs the Silver frontend (parse, semantic analysis, translation, consistency check, plugin work before
 * verification) on each file, then the Silver utilities a backend calls per program, and records per-phase
 * thread CPU time, wall time and allocated bytes. Usage: SilverBench LIST CORPUS OUT WARMPASSES PASSES ARM */
object SilverBench {
  class NoopVerifier extends Verifier {
    private var cfg: SilFrontendConfig = _
    def name = "silver-bench"; def version = ""; def buildVersion = ""; def copyright = ""
    override def signature = name
    def debugInfo(info: Seq[(String, Any)]): Unit = ()
    def dependencies = Nil
    def start(): Unit = (); def stop(): Unit = ()
    def verify(program: Program): VerificationResult = Success
    def parseCommandLine(args: Seq[String]): Unit = { cfg = new SilFrontendConfig(args, "silver-bench") { this.verify() } }
    def reporter = NoopReporter
    def config = cfg
  }

  class BenchFrontend extends SilFrontend {
    override val reporter = NoopReporter
    def createVerifier(fullCmd: String): Verifier = new NoopVerifier
    def configureVerifier(args: Seq[String]): SilFrontendConfig = { verifier.parseCommandLine(args); verifier.asInstanceOf[NoopVerifier].config }
    def setup(path: Path): Unit = { setVerifier(createVerifier("")); prepare(Seq(path.toString)); init(verifier); reset(path) }
  }

  val tmx = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  val gcs = ManagementFactory.getGarbageCollectorMXBeans.asScala.toSeq
  def gcMs: Long = gcs.map(_.getCollectionTime).sum

  final case class M(cpu: Long, wall: Long, alloc: Long, gc: Long)
  def measure(f: => Unit): M = {
    val id = Thread.currentThread().getId
    val c0 = tmx.getCurrentThreadCpuTime; val w0 = System.nanoTime(); val a0 = tmx.getThreadAllocatedBytes(id); val g0 = gcMs
    f
    M(tmx.getCurrentThreadCpuTime - c0, System.nanoTime() - w0, tmx.getThreadAllocatedBytes(id) - a0, gcMs - g0)
  }

  def backendUtilities(p: Program): Unit = {
    p.groundTypeInstances
    val members: Seq[Member] = p.methods ++ p.functions ++ p.predicates
    members.foreach { m =>
      QuantifiedPermissions.quantifiedFields(m, p)
      QuantifiedPermissions.quantifiedPredicates(m, p)
      QuantifiedPermissions.resourceTriggers(m, p)
    }
    QuantifiedPermissions.quantifiedMagicWands(p, p)
  }

  /** Runs one file and returns "status\tphases-json"; used by PairedLauncher through reflection. */
  def runFile(path: String): String = {
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].setLevel(Level.OFF)
    val fe = new BenchFrontend
    val phaseRes = scala.collection.mutable.ArrayBuffer.empty[(String, M)]
    var status = "ok"
    try {
      fe.setup(Paths.get(path))
      val total = measure {
        fe.phases.foreach { ph =>
          if (status == "ok") {
            phaseRes += ph.name -> measure(fe.runOnly(ph))
            if (fe.errors.nonEmpty) status = s"errors-after-${ph.name}"
          }
        }
        if (status == "ok") fe.program.foreach(p => phaseRes += "Backend utilities" -> measure(backendUtilities(p)))
      }
      phaseRes += "Total" -> total
    } catch { case t: Throwable => status = "exception:" + t.getClass.getSimpleName
      if (sys.env.contains("BENCH_DEBUG")) t.printStackTrace() }
    status + "\t" + phaseRes.map { case (n, m) => s""""$n":[${m.cpu},${m.wall},${m.alloc},${m.gc}]""" }.mkString(",")
  }

  def main(args: Array[String]): Unit = {
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].setLevel(Level.OFF)
    val Array(list, corpus, out, warmS, passesS, arm) = args.take(6)
    def read(l: String) = Files.readAllLines(Paths.get(l)).asScala.map(_.split("\t").head.trim).filter(_.nonEmpty).toSeq
    val measured = read(list)
    // Optional seventh argument: a separate list for the warm-up passes
    val warmFiles = if (args.length > 6) read(args(6)) else measured
    val w = new PrintWriter(new FileWriter(out, true))
    val passes = warmS.toInt + passesS.toInt
    for (pass <- 0 until passes; f <- (if (pass < warmS.toInt) warmFiles else measured)) {
      val path = Paths.get(corpus).resolve(f)
      val fe = new BenchFrontend
      val phaseRes = scala.collection.mutable.ArrayBuffer.empty[(String, M)]
      var status = "ok"
      try {
        fe.setup(path)
        val total = measure {
          fe.phases.foreach { ph =>
            if (status == "ok") {
              val m = measure(fe.runOnly(ph))
              phaseRes += ph.name -> m
              if (fe.errors.nonEmpty) status = s"errors-after-${ph.name}"
            }
          }
          if (status == "ok") fe.program.foreach(p => phaseRes += "Backend utilities" -> measure(backendUtilities(p)))
        }
        phaseRes += "Total" -> total
      } catch { case t: Throwable => status = "exception:" + t.getClass.getSimpleName }
      if (pass >= warmS.toInt) {
        val ph = phaseRes.map { case (n, m) => s""""$n":[${m.cpu},${m.wall},${m.alloc},${m.gc}]""" }.mkString(",")
        w.println(s"""{"arm":"$arm","pass":${pass - warmS.toInt},"file":"$f","status":"$status","phases":{$ph}}""")
        w.flush()
      }
    }
    w.close()
  }
}
