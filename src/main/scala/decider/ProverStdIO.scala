// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2021 ETH Zurich.

package viper.silicon.decider

import java.io._
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import com.typesafe.scalalogging.LazyLogging
import viper.silicon.common.config.Version
import viper.silicon.interfaces.decider.{Prover, Result, Sat, Unknown, Unsat}
import viper.silicon.reporting.{ExternalToolError, ProverInteractionFailed}
import viper.silicon.state.IdentifierFactory
import viper.silicon.state.terms._
import viper.silicon.verifier.Verifier
import viper.silver.verifier.{DefaultDependency => SilDefaultDependency}
import viper.silicon.{Config, Map, toMap}
import viper.silver.reporter.{ConfigurationConfirmation, InternalWarningMessage, QuantifierInstantiationsMessage, Reporter}
import viper.silver.verifier.Model

import scala.collection.mutable

abstract class ProverStdIO(uniqueId: String,
                    termConverter: TermToSMTLib2Converter,
                    identifierFactory: IdentifierFactory,
                    reporter: Reporter)
    extends Prover
       with LazyLogging {

  /* protected */ var pushPopScopeDepth = 0
  protected var lastTimeout: Int = -1
  protected var logfileWriter: PrintWriter = _
  protected var prover: Process = _
  protected var proverShutdownHook: Thread = _
  protected var input: BufferedReader = _
  protected var output: PrintWriter = _
  protected var allDecls: Seq[Decl] = Seq()
  protected var allEmits: Seq[String] = Seq()
  private var proofPortfolio: InjectivityProofPortfolio.Capture = _
  private var portfolioPaused = false
  private var portfolioGoal = false
  /* The first failure of a probe exchange. Once set, the channel framing is unknown, so no further
   * protocol I/O happens on this instance, and neither stop() nor reset() makes it usable again. */
  @volatile private var poisoned: Throwable = _
  private val poisonDisposing = new java.util.concurrent.atomic.AtomicBoolean(false)
  private val streamsClosing = new java.util.concurrent.atomic.AtomicBoolean(false)
  @volatile private var processDisposed = false
  @volatile private var streamsDisposed = false
  protected def poisonDisposalComplete: Boolean = processDisposed && streamsDisposed

  var proverPath: Path = _
  var lastReasonUnknown : String = _
  var lastModel : String = _

  def exeEnvironmentalVariable: String
  def dependencies: Seq[SilDefaultDependency]
  def startUpArgs: Seq[String]

  protected def setTimeout(timeout: Option[Int]): Unit
  protected def getProverPath: Path

  @inline
  private def readLineFromInput(): String = {
    usable()
    val line = input.readLine()

    if (line == null) {
      throw ProverInteractionFailed(uniqueId, s"Interaction with prover yielded null. This might indicate that the prover crashed.")
    }

    line
  }

  def version(): Version = {
    val versionPattern = """\(?\s*:version\s+"(.*?)(?:\s*-.*?)?"\)?""".r
    var line = ""

    writeLine("(get-info :version)")

    line = readLineFromInput()
    comment(line)

    line match {
      case versionPattern(v) => Version(v)
      case _ => throw ProverInteractionFailed(uniqueId, s"Unexpected output of prover while getting version: $line")
    }
  }

  def start(): Unit = {
    start(Verifier.config.proverArgs)
  }

  def start(userArgsString: Option[String]): Unit = {
    usable()
    // Calling `start()` multiple times in a row would leak memory and prover processes.
    if (proverShutdownHook != null) {
      throw new AssertionError("stop() should be called between any pair of start() calls")
    }
    pushPopScopeDepth = 0
    lastTimeout = -1
    portfolioPaused = false
    portfolioGoal = false
    proofPortfolio = if (this.isInstanceOf[Z3ProverStdIO] &&
      java.lang.Boolean.getBoolean("silicon.injectivityProofPortfolio") && userArgsString.isEmpty &&
      Verifier.config.counterexample.toOption.isEmpty && !debugMode)
      new InjectivityProofPortfolio.Capture else null
    logfileWriter = if (!Verifier.config.outputProverLog) null else viper.silver.utility.Common.PrintWriter(Verifier.config.proverLogFile(uniqueId).toFile)
    proverPath = getProverPath
    prover = createProverInstance(userArgsString)
    input = new BufferedReader(new InputStreamReader(prover.getInputStream))
    output = new PrintWriter(new BufferedWriter(new OutputStreamWriter(prover.getOutputStream)), true)
    // Register a shutdown hook to stop prover when the JVM gracefully terminates.
    // Note that if the JVM abruptly terminates (e.g. because of a SIGKILL)
    // this hook will not be executed and the prover process will be left running.
    proverShutdownHook = new Thread {
      override def run(): Unit = {
        if (prover.isAlive) {
          prover.destroyForcibly()
        }
      }
    }
    Runtime.getRuntime.addShutdownHook(proverShutdownHook)
  }

  protected def createProverInstance(userArgsString: Option[String]): Process = {
    // One can pass some options. This allows to check whether they have been received.
    val msg = s"Starting prover at location '$proverPath'"
    reporter report ConfigurationConfirmation(msg)
    logger debug msg

    val proverFile = proverPath.toFile

    if (!proverFile.isFile)
      throw ExternalToolError("Prover", s"Cannot run prover at location '$proverFile': not a file.")

    if (!proverFile.canExecute)
      throw ExternalToolError("Prover", s"Cannot run prover at location '$proverFile': file is not executable.")

    val userProvidedArgs: Array[String] = userArgsString match {
      case None =>
        Array()

      case Some(args) =>
        // One can pass some options. This allows to check whether they have been received.
        val msg = s"Additional command-line arguments are $args"
        reporter report ConfigurationConfirmation(msg)
        logger debug msg
        args.split(' ').map(_.trim)
    }

    val builder = new ProcessBuilder(proverPath.toFile.getPath +: startUpArgs ++: userProvidedArgs :_*)
    builder.redirectErrorStream(true)

    builder.start()
  }

  def reset(): Unit = {
    stop()
    start()
  }

  /* The statement input.close() does not always terminate (e.g. if there is data left to be read).
   * It therefore makes sense to first kill the prover process because then the channel is closed from
   * the other side first, resulting in the close() method to terminate.
   */
  def stop(): Unit = {
    if (poisoned != null) return disposePoisoned()
    this.synchronized {
      if (logfileWriter != null) {
        logfileWriter.flush()
      }
      if (output != null) {
        output.flush()
      }
      if (prover != null) {
        prover.destroyForcibly()
        prover.waitFor(10, TimeUnit.SECONDS) /* Makes the current thread wait until the process has been shut down */
      }

      if (logfileWriter != null) {
        logfileWriter.close()
      }
      if (input != null) {
        input.close()
      }
      if (output != null) {
        output.close()
      }

      if (proverShutdownHook != null) {
        // Deregister the shutdown hook, otherwise the prover process that has been stopped cannot be garbage collected.
        // Explanation: https://blog.creekorful.org/2020/03/classloader-and-memory-leaks/
        // Bug report: https://github.com/viperproject/silicon/issues/579
        Runtime.getRuntime.removeShutdownHook(proverShutdownHook)
        proverShutdownHook = null
      }
      if (proofPortfolio != null) proofPortfolio.clear()
      proofPortfolio = null
      portfolioGoal = false
      portfolioPaused = false
      allDecls = Seq()
      allEmits = Seq()
      preambleAssumptions = Seq()
    }
  }

  def push(n: Int = 1, timeout: Option[Int] = None): Unit = {
    setTimeout(timeout)
    if (proofPortfolio != null && !portfolioPaused) proofPortfolio.push(n)
    pushPopScopeDepth += n
    val cmd = (if (n == 1) "(push)" else "(push " + n + ")") + " ; " + pushPopScopeDepth
    writeLine(cmd)
    readSuccess()
  }

  def pop(n: Int = 1): Unit = {
    val cmd = (if (n == 1) "(pop)" else "(pop " + n + ")") + " ; " + pushPopScopeDepth
    if (proofPortfolio != null && !portfolioPaused) proofPortfolio.pop(n)
    pushPopScopeDepth -= n
    writeLine(cmd)
    readSuccess()
  }

  def emit(content: String): Unit = {
    if (debugMode) {
      allEmits :+= content
    }
    if (proofPortfolio != null && (content.startsWith("(push") || content.startsWith("(pop") || content.startsWith("(reset")))
      proofPortfolio.clear()
    writeLine(content)
    if (proofPortfolio != null) proofPortfolio.markExternal()
    readSuccess()
  }

  def getAllEmits() : Seq[String] = allEmits

  override def setOption(name: String, value: String): String = {
    writeLine(s"(get-option :${name})")
    val oldVal = readLine()
    if (oldVal == "unsupported")
      throw ProverInteractionFailed(uniqueId, s"Prover does not support option $name")
    emit(s"(set-option :$name $value)")
    oldVal
  }

//  private val quantificationLogger = bookkeeper.logfiles("quantification-problems")

  def assume(term: Term): Unit = {
//    /* Detect certain simple problems with quantifiers.
//     * Note that the current checks don't take in account whether or not a
//     * quantification occurs in positive or negative position.
//     */
//    term.deepCollect{case q: Quantification => q}.foreach(q => {
//      val problems = QuantifierSupporter.detectQuantificationProblems(q)
//
//      if (problems.nonEmpty) {
//        quantificationLogger.println(s"\n\n${q.toString(true)}")
//        quantificationLogger.println("Problems:")
//        problems.foreach(p => quantificationLogger.println(s"  $p"))
//      }
//    })

    val inverse = if (proofPortfolio != null && proofPortfolio.enabled)
      InjectivityProofPortfolio.recognize(term, termConverter) else None
    assume(termConverter.convert(term))
    inverse.foreach(inv => proofPortfolio.register(inv))
  }

  def assume(term: String): Unit = {
//    bookkeeper.assumptionCounter += 1

    writeLine("(assert " + term + ")")
    readSuccess()
  }

  def assert(goal: Term, timeout: Option[Int] = None): Boolean = {
    portfolioGoal = proofPortfolio != null && proofPortfolio.eligible &&
      Verifier.config.assertionMode() == Config.AssertionMode.PushPop && timeout.getOrElse(Verifier.config.proverTimeout) == 0 && InjectivityProofPortfolio.eligibleGoal(goal) &&
      proofPortfolio.ready(termConverter.convert(goal), termConverter)
    try assert(termConverter.convert(goal), timeout)
    finally portfolioGoal = false
  }

  def assert(goal: String, timeout: Option[Int]): Boolean = {
//    bookkeeper.assertionCounter += 1

    val (result, duration) = Verifier.config.assertionMode() match {
      case Config.AssertionMode.SoftConstraints => assertUsingSoftConstraints(goal, timeout)
      case Config.AssertionMode.PushPop => assertUsingPushPop(goal, timeout)
    }

    comment(s"${viper.silver.reporter.format.formatMillisReadably(duration)}")
    comment("(get-info :all-statistics)")

    result
  }

  protected def assertUsingPushPop(goal: String, timeout: Option[Int]): (Boolean, Long) = {
    if (portfolioGoal) {
      val start = System.currentTimeMillis()
      val proved = probe(goal)
      if (proved.isDefined) return (proved.get, System.currentTimeMillis() - start)
    }
    push()
    setTimeout(timeout)

    writeLine("(assert (not " + goal + "))")
    readSuccess()

    val startTime = System.currentTimeMillis()
    writeLine("(check-sat)")
    val result = readUnsat()
    val endTime = System.currentTimeMillis()

    if (!result) {
      retrieveAndSaveModel()
      retrieveReasonUnknown()
    }

    pop()

    (result, endTime - startTime)
  }

  /* Attempts `goal` under a 10ms wall budget with an unlimited resource budget, independently of the
   * timeout mode. The physical budgets are read from the prover because the cached `lastTimeout` does not
   * determine them, and are restored verbatim together with the scope depth and the cache before a result
   * is accepted or the caller falls back. Any failure or interruption poisons the channel: the original
   * exception propagates, and neither restoration nor fallback is attempted on an uncertain channel. */
  private def probe(goal: String): Option[Boolean] = {
    def reply(): String = {
      val line = readLine()
      if (Thread.currentThread().isInterrupted) throw new InterruptedException()
      line
    }
    def exchange(cmd: String): Unit = {
      writeLine(cmd)
      val answer = reply()
      if (answer != "success")
        throw ProverInteractionFailed(uniqueId, s"Unexpected output of prover. Expected 'success' but found: $answer")
    }
    val savedTimeout = lastTimeout
    val savedDepth = pushPopScopeDepth
    portfolioPaused = true
    try {
      // `unsupported` is not answered: Z3 reports it with an additional diagnostic line on the merged stream.
      val budgets = Seq("timeout", "rlimit").map { option =>
        writeLine(s"(get-option :$option)")
        val value = reply()
        val parsed = if (value.matches("0|[1-9][0-9]{0,9}")) value.toLong else -1L
        if (parsed < 0 || parsed > 0xFFFFFFFFL)
          throw ProverInteractionFailed(uniqueId, s"Unexpected value of option $option: $value")
        option -> parsed
      }
      pushPopScopeDepth = savedDepth + 1
      exchange(s"(push) ; ${savedDepth + 1}")
      exchange("(set-option :rlimit 0)")
      exchange("(set-option :timeout 10)")
      exchange(s"(assert (not $goal))")
      writeLine("(check-sat)")
      val result = reply() match {
        case "unsat" => Some(true)
        case "sat" =>
          // Model/debug configurations are excluded from portfolio eligibility.
          if (Verifier.config.counterexample.toOption.isDefined || debugMode)
            throw ProverInteractionFailed(uniqueId, "Ineligible probe diagnostics")
          if (Verifier.config.reportReasonUnknown()) {
            writeLine("(get-info :reason-unknown)")
            val reason = reply()
            if (!reason.matches("""\(:reason-unknown "(?:[^"]|"")*"\)"""))
              throw ProverInteractionFailed(uniqueId, s"Unexpected probe diagnostic: $reason")
            lastReasonUnknown = reason.substring(18, reason.length - 2)
          }
          Some(false)
        case "unknown" => None
        case other => throw ProverInteractionFailed(uniqueId, s"Unexpected probe output: $other")
      }
      exchange(s"(pop) ; ${savedDepth + 1}")
      pushPopScopeDepth = savedDepth
      budgets.foreach { case (option, value) => exchange(s"(set-option :$option $value)") }
      lastTimeout = savedTimeout
      val proved = result.orElse(if (proofPortfolio.prove(goal, proverPath.toString)) Some(true) else None)
      if (Thread.currentThread().isInterrupted) throw new InterruptedException()
      proved
    } catch {
      case failure: Throwable =>
        try poison(failure) catch { case cleanup: Throwable => cleanupFailed(cleanup) }
        throw failure
    } finally portfolioPaused = false
  }

  private def usable(): Unit =
    if (poisoned != null)
      throw ProverInteractionFailed(uniqueId, s"Prover channel is unusable after a failed exchange: $poisoned")

  private def poison(failure: Throwable): Unit = if (poisoned == null) {
    poisoned = failure
    if (proofPortfolio != null) proofPortfolio.clear()
    disposePoisoned()
  }

  protected def attachCleanupFailure(failure: Throwable, cleanup: Throwable): Unit = failure.addSuppressed(cleanup)
  private def cleanupFailed(cleanup: Throwable): Unit = {
    def warn(): Unit = try { reporter report InternalWarningMessage(s"Incomplete prover disposal: $cleanup") } catch { case _: Throwable => () }
    try { if (cleanup ne poisoned) attachCleanupFailure(poisoned, cleanup)
      if (!poisoned.getSuppressed.contains(cleanup)) warn()
    } catch { case _: Throwable => warn() }
  }
  protected def disposalThread(action: Runnable): Thread = new Thread(action, s"prover-$uniqueId-disposal")

  /* Reaping waits at most one second; stream closure runs on a daemon. Incomplete cleanup is retried by
   * stop(), without permitting protocol reuse. Failure attachment itself must not escape cleanup. */
  private def disposePoisoned(): Unit = if (poisonDisposing.compareAndSet(false, true)) try {
    val process = prover
    if (!processDisposed) {
      var interrupted = Thread.interrupted()
      try {
        if (process != null) process.destroyForcibly()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (process != null && process.isAlive && System.nanoTime() < until)
          try process.waitFor(until - System.nanoTime(), TimeUnit.NANOSECONDS)
          catch { case _: InterruptedException => interrupted = true }
        if (process != null && process.isAlive) {
          val msg = s"Prover $uniqueId was not reaped after a failed exchange"
          reporter report InternalWarningMessage(msg)
          logger warn msg
        } else {
          if (proverShutdownHook != null) {
            Runtime.getRuntime.removeShutdownHook(proverShutdownHook)
            proverShutdownHook = null
          }
          processDisposed = true
        }
      } catch {
        case cleanup: Throwable => cleanupFailed(cleanup)
      } finally if (interrupted) Thread.currentThread().interrupt()
    }
    if (!streamsDisposed && streamsClosing.compareAndSet(false, true)) try {
      val raw = if (process == null) Seq.empty else Seq(process.getInputStream, process.getOutputStream, process.getErrorStream)
      val streams = (raw ++ Seq[Closeable](logfileWriter, input, output)).filter(_ != null)
      val closer = disposalThread(() => {
        try {
          var complete = true
          streams.foreach { stream =>
            try stream.close() catch { case cleanup: Throwable => complete = false; cleanupFailed(cleanup) }
          }
          streamsDisposed = complete
        } catch { case cleanup: Throwable => cleanupFailed(cleanup) }
        finally streamsClosing.set(false)
      })
      closer.setDaemon(true)
      closer.start()
    } catch { case cleanup: Throwable => streamsClosing.set(false); cleanupFailed(cleanup) }
    allDecls = Seq()
    allEmits = Seq()
    preambleAssumptions = Seq()
  } finally poisonDisposing.set(false)

  def saturate(data: Option[Config.ProverStateSaturationTimeout]): Unit = {
    data match {
      case Some(Config.ProverStateSaturationTimeout(timeout, comment)) => saturate(timeout, comment)
      case None => /* Don't do anything */
    }
  }

  def saturate(timeout: Int, comment: String): Unit = {
    this.comment(s"State saturation: $comment")
    setTimeout(Some(timeout))
    writeLine("(check-sat)")
    readLine()
  }

  protected def retrieveAndSaveModel(): Unit = {
    if (Verifier.config.counterexample.toOption.isDefined) {
      writeLine("(get-model)")

      var model = readModel("\n").trim()
      if (model.startsWith("\"")) {
        model = model.replaceAll("\"", "")
      }
      lastModel = model
    }
  }

  protected def retrieveReasonUnknown(): Unit = {
    if (Verifier.config.reportReasonUnknown()) {
      writeLine("(get-info :reason-unknown)")
      var result = readLine()
      if (result.startsWith("(:reason-unknown \""))
        result = result.substring(18, result.length - 2)
      lastReasonUnknown = result
    }
  }

  override def hasModel(): Boolean = {
    lastModel != null
  }

  override def isModelValid(): Boolean = {
    lastModel != null && !lastModel.contains("model is not available")
  }

  protected def assertUsingSoftConstraints(goal: String, timeout: Option[Int]): (Boolean, Long) = {
    setTimeout(timeout)

    val guard = fresh("grd", Nil, sorts.Bool)
    val guardApp = App(guard, Nil)

    writeLine(s"(assert (=> $guardApp (not $goal)))")
    readSuccess()

    val startTime = System.currentTimeMillis()
    writeLine(s"(check-sat $guardApp)")
    val result = readUnsat()
    val endTime = System.currentTimeMillis()

    if (!result) {
      retrieveAndSaveModel()
    }

    (result, endTime - startTime)
  }

  def check(timeout: Option[Int] = None): Result = {
    setTimeout(timeout)

    writeLine("(check-sat)")

    readLine() match {
      case "sat" => Sat
      case "unsat" => Unsat
      case "unknown" => Unknown
    }
  }

  def statistics(): Map[String, String] = {
    var repeat = true
    var line = ""
    var stats = scala.collection.immutable.SortedMap[String, String]()
    val entryPattern = """\(?\s*:([A-za-z\-]+)\s+((?:\d+\.)?\d+)\)?""".r

    writeLine("(get-info :all-statistics)")

    do {
      line = readLineFromInput()
      comment(line)

      /* Check that the first line starts with "(:". */
      if (line.isEmpty && !line.startsWith("(:"))
        throw ProverInteractionFailed(uniqueId, s"Unexpected output of prover while reading statistics: $line")

      line match {
        case entryPattern(entryName, entryNumber) =>
          stats = stats + (entryName -> entryNumber)
        case _ =>
      }

      repeat = !line.endsWith(")")
    } while (repeat)

    toMap(stats)
  }

  def comment(str: => String): Unit = {
    if (logfileWriter != null) {
      val sanitisedStr =
        str.replaceAll("\r", "")
           .replaceAll("\n", "\n; ")

      logToFile("; " + sanitisedStr)
    }
  }

  def fresh(name: String, argSorts: Seq[Sort], resultSort: Sort): Fun = {
    val id = identifierFactory.fresh(name)
    val fun = Fun(id, argSorts, resultSort)
    val decl = FunctionDecl(fun)

    emit(termConverter.convert(decl))
    if (proofPortfolio != null && argSorts.isEmpty) proofPortfolio.global(Var(id, resultSort, false))

    fun
  }

  def declare(decl: Decl): Unit = {
    val str = termConverter.convert(decl)
    if (debugMode)
      allDecls = allDecls :+ decl
    emit(str)
    if (proofPortfolio != null) decl match {
      case FunctionDecl(v: Var) => proofPortfolio.global(v)
      case _ => ()
    }
  }

//  def resetAssertionCounter() { bookkeeper.assertionCounter = 0 }
//  def resetAssumptionCounter() { bookkeeper.assumptionCounter = 0 }

//  def resetCounters() {
//    resetAssertionCounter()
//    resetAssumptionCounter()
//  }

  /* TODO: Handle multi-line output, e.g. multiple error messages. */

  protected def readSuccess(): Unit = {
    val answer = readLine()

    if (answer != "success")
      throw ProverInteractionFailed(uniqueId, s"Unexpected output of prover. Expected 'success' but found: $answer")
  }

  protected def readUnsat(): Boolean = readLine() match {
    case "unsat" => true
    case "sat" => false
    case "unknown" => false

    case result =>
      throw ProverInteractionFailed(uniqueId, s"Unexpected output of prover while trying to refute an assertion: $result")
  }

  protected def readModel(separator: String): String = {
    try {
      var endFound = false
      val result = new mutable.StringBuilder
      var firstTime = true
      while (!endFound) {
        val nextLine = readLineFromInput()
        if (nextLine.trim().endsWith("\"") || (firstTime && !nextLine.startsWith("\""))) {
          endFound = true
        }
        result.append(separator).append(nextLine)
        firstTime = false
      }
      result.result()
    } catch {
      case e: Exception =>
        println("Error reading model: " + e)
        ""
    }
  }

  protected def readLine(): String = {
    var repeat = true
    var result = ""

    while (repeat) {
      result = readLineFromInput()
      if (result.toLowerCase != "success") comment(result)

      val warning = result.startsWith("WARNING")
      if (warning) {
        val msg = s"Prover warning: $result"
        reporter report InternalWarningMessage(msg)
        logger warn msg
      }

      // When `smt.qi.profile` is `true`, Z3 periodically reports the quantifier instantiations using the format
      // `[quantifier_instances] "<quantifier_id>" : <instances> : <maximum generation> : <maximum cost>`.
      // See: https://github.com/Z3Prover/z3/issues/4522
      val qiProfile = result.startsWith("[quantifier_instances]")
      if (qiProfile) {
        val qi_info = result.stripPrefix("[quantifier_instances]").split(":").map(_.trim)
        if (qi_info.length != 4) {
          val msg = s"Invalid quantifier instances line from prover: $result"
          reporter report InternalWarningMessage(msg)
          logger warn msg
        } else {
          reporter report QuantifierInstantiationsMessage(qi_info(0), qi_info(1).toInt, qi_info(2).toInt, qi_info(3).toInt)
          logger info result
        }
      }

      repeat = warning || qiProfile
    }

    result
  }

  protected def logToFile(str: String): Unit = {
    if (logfileWriter != null) {
      logfileWriter.println(str)
    }
  }

  protected def writeLine(out: String): Unit = {
    usable()
    if (proofPortfolio != null && !portfolioPaused) proofPortfolio.record(out)
    logToFile(out)
    output.println(out)
  }

  override def getModel(): Model = Model(lastModel)

  override def getReasonUnknown(): String = lastReasonUnknown

  override def clearLastAssert(): Unit = {
    lastReasonUnknown = null
    lastModel = null
  }

  def getAllDecls(): Seq[Decl] = allDecls
}
