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
import viper.silicon.verifier.{Cleanup, Verifier}
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
  protected var userArgs: Option[String] = None
  /* The command-line arguments of the running prover process; see [[retryArgs]] */
  protected var processArgs: Option[String] = None
  /* The number of assertions retried in a fresh prover since the prover was started */
  protected var retries: Int = 0
  protected val retryPolicy: AssertRetryPolicy = AssertRetryPolicy.current
  /* The commands that make up the prover's current state, one buffer per push-pop scope, such that
   * the state can be rebuilt after a prover restart. See [[restartAfterMemoryOut]].
   */
  protected var scopedCommands: Vector[mutable.ArrayBuffer[String]] = Vector(mutable.ArrayBuffer())
  protected var lastCheckRanOutOfMemory: Boolean = false
  /* Whether the current refutation attempt is retried in a fresh prover if it reaches its timeout */
  protected var isRetryableAttempt: Boolean = false
  protected var lastCheckReachedTimeout: Boolean = false
  /* The prover's answer to the most recent refutation attempt: unsat, sat or unknown */
  protected var lastCheckSatAnswer: String = _
  /* Whether the prover was restarted after running out of memory since the last refutation attempt */
  protected var ranOutOfMemorySinceLastCheck: Boolean = false
  /* The most recent command sent to the prover, to be resent if the prover runs out of memory on it */
  protected var lastCommand: String = _
  /* If defined, the prover runs out of memory on the state up to and including this scope depth, even
   * after a restart. The prover is then suspended: commands are recorded but not sent, and every check is
   * answered as unknown, until the scope is popped.
   */
  protected var suspendedFromDepth: Option[Int] = None

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
    // Calling `start()` multiple times in a row would leak memory and prover processes.
    if (proverShutdownHook != null) {
      throw new AssertionError("stop() should be called between any pair of start() calls")
    }
    pushPopScopeDepth = 0
    lastTimeout = -1
    userArgs = userArgsString
    processArgs = userArgsString
    retries = 0
    scopedCommands = Vector(mutable.ArrayBuffer())
    ranOutOfMemorySinceLastCheck = false
    suspendedFromDepth = None
    try {
      logfileWriter = if (!Verifier.config.outputProverLog) null else viper.silver.utility.Common.PrintWriter(Verifier.config.proverLogFile(uniqueId).toFile)
      proverPath = getProverPath
      prover = createProverInstance(userArgsString)
      connectToProver()
      // Register a shutdown hook to stop prover when the JVM gracefully terminates.
      // Note that if the JVM abruptly terminates (e.g. because of a SIGKILL)
      // this hook will not be executed and the prover process will be left running.
      proverShutdownHook = new Thread {
        override def run(): Unit = {
          // Read the field: a memory-out restart replaces the process.
          val process = prover
          if (process != null && process.isAlive) {
            process.destroyForcibly()
          }
        }
      }
      Runtime.getRuntime.addShutdownHook(proverShutdownHook)
    } catch {
      case e: Throwable =>
        // A partially started prover must not outlive the failed start.
        try stop()
        catch { case suppressed: Exception => e.addSuppressed(suppressed) }
        throw e
    }
  }

  protected def connectToProver(): Unit = {
    input = new BufferedReader(new InputStreamReader(prover.getInputStream))
    output = new PrintWriter(new BufferedWriter(new OutputStreamWriter(prover.getOutputStream)), true)
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
    this.synchronized {
      // Detach the resources first, so that a repeated stop() is a no-op and a failing step cannot
      // leave them half-released for a later call.
      val writer = logfileWriter
      val process = prover
      val in = input
      val out = output
      val hook = proverShutdownHook
      logfileWriter = null
      prover = null
      input = null
      output = null
      proverShutdownHook = null
      allDecls = Seq()
      allEmits = Seq()
      scopedCommands = Vector(mutable.ArrayBuffer())
      preambleAssumptions = Seq()

      Cleanup.run(
        () => if (writer != null) writer.flush(),
        () => if (out != null) out.flush(),
        () => if (process != null) {
          process.destroyForcibly()
          process.waitFor(10, TimeUnit.SECONDS) /* Makes the current thread wait until the process has been shut down */
          ()
        },
        () => if (writer != null) writer.close(),
        () => if (in != null) in.close(),
        () => if (out != null) out.close(),
        // Deregister the shutdown hook, otherwise the prover process that has been stopped cannot be garbage collected.
        // Explanation: https://blog.creekorful.org/2020/03/classloader-and-memory-leaks/
        // Bug report: https://github.com/viperproject/silicon/issues/579
        () => if (hook != null) { Runtime.getRuntime.removeShutdownHook(hook); () }
      )
    }
  }

  def push(n: Int = 1, timeout: Option[Int] = None): Unit = {
    setTimeout(timeout)
    pushPopScopeDepth += n
    val cmd = (if (n == 1) "(push)" else "(push " + n + ")") + " ; " + pushPopScopeDepth
    writeLine(cmd)
    readSuccess()
    scopedCommands ++= Vector.fill(n)(mutable.ArrayBuffer[String]())
  }

  def pop(n: Int = 1): Unit = {
    val cmd = (if (n == 1) "(pop)" else "(pop " + n + ")") + " ; " + pushPopScopeDepth
    pushPopScopeDepth -= n
    writeLine(cmd)
    readSuccess()
    /* Declarations are global (see the prover config), hence only assertions are popped */
    val popped = scopedCommands.takeRight(n).flatten.filterNot(_.startsWith("(assert"))
    scopedCommands = scopedCommands.dropRight(n)
    scopedCommands.last ++= popped
    if (suspendedFromDepth.exists(_ > scopedCommands.length - 1)) {
      comment(s"Resuming prover $uniqueId")
      restartAndReplay()
    }
  }

  def emit(content: String): Unit = {
    if (debugMode) {
      allEmits :+= content
    }
    writeLine(content)
    readSuccess()
    scopedCommands.last += content
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

    assume(termConverter.convert(term))
  }

  def assume(term: String): Unit = {
//    bookkeeper.assumptionCounter += 1

    val cmd = "(assert " + term + ")"
    writeLine(cmd)
    readSuccess()
    scopedCommands.last += cmd
  }

  def assert(goal: Term, timeout: Option[Int] = None): Boolean =
    assert(termConverter.convert(goal), timeout)

  def assert(goal: String, timeout: Option[Int]): Boolean = {
//    bookkeeper.assertionCounter += 1

    val retryTimeout = Verifier.config.assertRetryTimeout()
    val (result, duration) =
      if (timeout.isEmpty && Verifier.config.proverTimeout == 0 && retryTimeout > 0) {
        /* Z3's search depends on the queries that preceded it in the same prover and on its random seed.
         * An assertion that it does not prove quickly is retried, without a timeout, in a fresh prover with the
         * same state and seed. A bounded retry fails assertions that an unbounded search would prove, and a new
         * seed gained nothing in the 2026-10-28 ablation (docs/perf/ideas.md); [[AssertRetryPolicy]] can still
         * select both.
         */
        isRetryableAttempt = true
        val (firstResult, firstDuration) =
          try assertInMode(goal, Some(retryTimeout)) finally isRetryableAttempt = false
        if (firstResult || lastCheckRanOutOfMemory || !lastCheckReachedTimeout) {
          (firstResult, firstDuration)
        } else {
          comment(s"Retrying the assertion in a fresh prover")
          retries += 1
          processArgs = if (retryPolicy.newSeed) retryArgs(userArgs, retries) else userArgs
          val restartStart = System.currentTimeMillis()
          restartAndReplay()
          val restartDuration = System.currentTimeMillis() - restartStart
          val retryBound = if (retryPolicy.boundedFinal) Some(retryTimeout * ProverStdIO.RetryTimeoutFactor) else None
          val (retryResult, retryDuration) = assertInMode(goal, retryBound)
          val answer = if (lastCheckRanOutOfMemory) "out of memory" else lastCheckSatAnswer
          val msg = (s"Prover $uniqueId retried an assertion in a fresh prover: first attempt $firstDuration ms, "
                     + s"restart and replay $restartDuration ms, retry $retryDuration ms, answer $answer")
          reporter report InternalWarningMessage(msg)
          logger info msg
          (retryResult, firstDuration + retryDuration)
        }
      } else {
        assertInMode(goal, timeout)
      }

    comment(s"${viper.silver.reporter.format.formatMillisReadably(duration)}")
    comment("(get-info :all-statistics)")

    result
  }

  /* The command-line arguments for a prover that retries an assertion for the [[retry]]-th time, such that
   * the retry does not repeat the search of the first attempt
   */
  protected def retryArgs(args: Option[String], retry: Int): Option[String] = args

  private def assertInMode(goal: String, timeout: Option[Int]): (Boolean, Long) = {
    Verifier.config.assertionMode() match {
      case Config.AssertionMode.SoftConstraints => assertUsingSoftConstraints(goal, timeout)
      case Config.AssertionMode.PushPop => assertUsingPushPop(goal, timeout)
    }
  }

  /* Records whether the prover gave up on a failed first attempt because of its timeout */
  protected def updateLastCheckReachedTimeout(): Unit = {
    lastCheckReachedTimeout = false
    if (isRetryableAttempt) {
      writeLine("(get-info :reason-unknown)")
      val result = readLine()
      if (isOutOfMemory(result)) {
        restartAfterMemoryOut()
        lastCheckRanOutOfMemory = true
        ranOutOfMemorySinceLastCheck = false
      } else {
        lastCheckReachedTimeout =
          result.contains("resource") || result.contains("timeout") || result.contains("canceled")
      }
    }
  }

  protected def assertUsingPushPop(goal: String, timeout: Option[Int]): (Boolean, Long) = {
    push()
    setTimeout(timeout)

    writeLine("(assert (not " + goal + "))")
    readSuccess()

    val startTime = System.currentTimeMillis()
    writeLine("(check-sat)")
    val result = readUnsat()
    val endTime = System.currentTimeMillis()

    if (!result && !lastCheckRanOutOfMemory) {
      retrieveAndSaveModel()
      if (!lastCheckRanOutOfMemory) retrieveReasonUnknown()
      if (!lastCheckRanOutOfMemory) updateLastCheckReachedTimeout()
    }

    pop()

    (result, endTime - startTime)
  }

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
    readCheckSatResult()
  }

  protected def retrieveAndSaveModel(): Unit = {
    if (Verifier.config.counterexample.toOption.isDefined) {
      writeLine("(get-model)")

      var model = readModel("\n").trim()
      if (isOutOfMemory(model)) {
        restartAfterMemoryOut()
        lastCheckRanOutOfMemory = true
        ranOutOfMemorySinceLastCheck = false
        return
      }
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
      if (isOutOfMemory(result)) {
        restartAfterMemoryOut()
        lastCheckRanOutOfMemory = true
        ranOutOfMemorySinceLastCheck = false
        return
      }
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

    val cmd = s"(assert (=> $guardApp (not $goal)))"
    writeLine(cmd)
    readSuccess()
    scopedCommands.last += cmd

    val startTime = System.currentTimeMillis()
    writeLine(s"(check-sat $guardApp)")
    val result = readUnsat()
    val endTime = System.currentTimeMillis()

    if (!result && !lastCheckRanOutOfMemory) {
      retrieveAndSaveModel()
      if (!lastCheckRanOutOfMemory) updateLastCheckReachedTimeout()
    }

    (result, endTime - startTime)
  }

  def check(timeout: Option[Int] = None): Result = {
    setTimeout(timeout)

    writeLine("(check-sat)")

    readCheckSatResult() match {
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

    fun
  }

  def declare(decl: Decl): Unit = {
    val str = termConverter.convert(decl)
    if (debugMode)
      allDecls = allDecls :+ decl
    emit(str)
  }

//  def resetAssertionCounter() { bookkeeper.assertionCounter = 0 }
//  def resetAssumptionCounter() { bookkeeper.assumptionCounter = 0 }

//  def resetCounters() {
//    resetAssertionCounter()
//    resetAssumptionCounter()
//  }

  /* TODO: Handle multi-line output, e.g. multiple error messages. */

  /* Reads the answer to the last command. If the prover ran out of memory, it is restarted and, since the
   * command is not part of the replayed state, the command is sent again.
   */
  protected def readSuccess(): Unit = {
    if (suspendedFromDepth.isDefined) return

    val answer = readLine()

    if (isOutOfMemory(answer)) {
      restartAfterMemoryOut()
      if (suspendedFromDepth.isEmpty && !replay(lastCommand)) suspend(scopedCommands.length - 1)
    } else {
      expectSuccess(answer)
    }
  }

  private def expectSuccess(answer: String): Unit = {
    if (answer != "success")
      throw ProverInteractionFailed(uniqueId, s"Unexpected output of prover. Expected 'success' but found: $answer")
  }

  protected def isOutOfMemory(answer: String): Boolean =
    answer.startsWith("(error") && answer.contains("out of memory")

  /* A refutation attempt that fails after a memory-out restart is also attributed to the memory-out */
  protected def readUnsat(): Boolean = {
    val outOfMemoryBefore = ranOutOfMemorySinceLastCheck
    val result = readCheckSatResult()
    lastCheckSatAnswer = result
    /* A memory-out on this check is attributed to it, not to the next one */
    ranOutOfMemorySinceLastCheck = false

    result match {
      case "unsat" => true
      case "sat" | "unknown" =>
        if (outOfMemoryBefore && !lastCheckRanOutOfMemory) {
          lastCheckRanOutOfMemory = true
          lastReasonUnknown = "out of memory"
        }
        false

      case result =>
        throw ProverInteractionFailed(uniqueId, s"Unexpected output of prover while trying to refute an assertion: $result")
    }
  }

  /* Reads the answer to a (check-sat). If the prover ran out of memory, it is restarted and the answer
   * is taken to be "unknown"; the caller can tell from [[lastCheckRanOutOfMemory]].
   */
  protected def readCheckSatResult(): String = {
    if (suspendedFromDepth.isDefined) {
      lastCheckRanOutOfMemory = true
      lastModel = null
      lastReasonUnknown = "out of memory"
      return "unknown"
    }

    val result = readLine()
    lastCheckRanOutOfMemory = isOutOfMemory(result)

    if (lastCheckRanOutOfMemory) {
      restartAfterMemoryOut()
      "unknown"
    } else {
      result
    }
  }

  /* Z3 exits after running out of memory (see its option memory_max_size). Restart the prover and
   * replay the commands that make up its current state, so that verification can continue.
   */
  protected def restartAfterMemoryOut(): Unit = {
    val msg = s"Prover $uniqueId ran out of memory, restarting it"
    reporter report InternalWarningMessage(msg)
    logger warn msg
    comment(msg)

    restartAndReplay()

    lastModel = null
    lastReasonUnknown = "out of memory"
    ranOutOfMemorySinceLastCheck = true
  }

  private def restartAndReplay(): Unit = {
    prover.destroyForcibly()
    prover.waitFor(10, TimeUnit.SECONDS)
    input.close()
    output.close()
    prover = createProverInstance(processArgs)
    connectToProver()
    lastTimeout = -1
    suspendedFromDepth = None

    /* The replayed commands are not logged again. A (push) makes Z3 process the preceding scope. */
    comment(s"Replaying the prover state (${scopedCommands.length - 1} scopes)")
    var depth = 0
    while (suspendedFromDepth.isEmpty && depth < scopedCommands.length) {
      if (depth > 0 && !replay("(push)")) suspend(depth - 1)
      else if (!scopedCommands(depth).forall(replay)) suspend(depth)
      depth += 1
    }
  }

  /* Sends a command without logging it; returns false if the prover ran out of memory */
  private def replay(command: String): Boolean = {
    output.println(command)
    val answer = readLine()
    if (isOutOfMemory(answer)) {
      false
    } else {
      expectSuccess(answer)
      true
    }
  }

  private def suspend(depth: Int): Unit = {
    val msg = s"Prover $uniqueId ran out of memory again after a restart, suspending it until scope $depth is popped"
    reporter report InternalWarningMessage(msg)
    logger warn msg
    comment(msg)

    prover.destroyForcibly()
    prover.waitFor(10, TimeUnit.SECONDS)
    suspendedFromDepth = Some(depth)
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
    logToFile(out)
    lastCommand = out
    if (suspendedFromDepth.isEmpty) output.println(out)
  }

  override def getModel(): Model = Model(lastModel)

  override def getReasonUnknown(): String = lastReasonUnknown

  override def ranOutOfMemory(): Boolean = lastCheckRanOutOfMemory

  override def clearLastAssert(): Unit = {
    lastReasonUnknown = null
    lastModel = null
  }

  def getAllDecls(): Seq[Decl] = allDecls
}

object ProverStdIO {
  /* The retry of an assertion in a fresh prover is bounded by this multiple of the first-attempt timeout */
  val RetryTimeoutFactor = 4
}
