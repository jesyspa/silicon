// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 ETH Zurich.

package viper.silicon.tests

import java.io.{BufferedReader, InputStream, OutputStream, PrintWriter, StringReader, StringWriter}
import java.util.concurrent.TimeUnit
import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Silicon
import viper.silicon.decider.{TermToSMTLib2Converter, Z3ProverStdIO}
import viper.silicon.state.DefaultIdentifierFactory
import viper.silicon.verifier.Cleanup
import viper.silver.reporter.NoopReporter

/** Teardown releases every resource even when individual steps fail or the thread is interrupted. */
class VerifierCleanupTests extends AnyFunSuite {
  test("Cleanup attempts every step and rethrows the first failure with later ones suppressed") {
    val first = new RuntimeException("first")
    val second = new IllegalStateException("second")
    var ran = Vector.empty[Int]

    val thrown = intercept[RuntimeException] {
      Cleanup.run(
        () => { ran :+= 1; throw first },
        () => { ran :+= 2 },
        () => { ran :+= 3; throw second })
    }

    assert(ran == Vector(1, 2, 3))
    assert(thrown eq first)
    assert(thrown.getSuppressed.toSeq == Seq(second))
  }

  test("Cleanup runs steps uninterrupted, prefers a real failure and restores the interrupt") {
    val interruption = new InterruptedException("interrupted")
    val failure = new RuntimeException("failure")
    var interruptedDuringStep = true

    Thread.currentThread().interrupt()
    val thrown = intercept[RuntimeException] {
      Cleanup.run(
        () => { interruptedDuringStep = Thread.currentThread().isInterrupted },
        () => throw interruption,
        () => throw failure)
    }

    assert(Thread.interrupted(), "the interrupt flag must be restored")
    assert(!interruptedDuringStep)
    assert(thrown eq failure)
    assert(thrown.getSuppressed.toSeq == Seq(interruption))
  }

  test("Stopping a prover destroys its process even when flushing fails, and only once") { withConfig {
    val process = new FakeProcess
    val prover = new FakeProver(process)
    prover.install(failFlush = true)
    val hook = prover.hook

    val thrown = intercept[RuntimeException](prover.stop())
    assert(thrown.getMessage == "flush failed")
    assert(process.destroyCount == 1)
    assert(prover.outputClosed && prover.inputClosed)
    assert(prover.hook == null)
    assert(!Runtime.getRuntime.removeShutdownHook(hook), "the shutdown hook must already be deregistered")

    prover.stop()
    assert(process.destroyCount == 1)
  }}

  test("Stopping a prover on an interrupted thread still waits for the process and keeps the interrupt") { withConfig {
    val process = new FakeProcess
    val prover = new FakeProver(process)
    prover.install(failFlush = false)

    Thread.currentThread().interrupt()
    prover.stop()

    assert(Thread.interrupted(), "the interrupt flag must be restored")
    assert(process.destroyCount == 1)
    assert(process.waitedUninterrupted)
    assert(prover.outputClosed && prover.inputClosed)
  }}

  test("A prover whose start fails half-way releases its process and can be started again") { withConfig {
    val failed = new FakeProcess
    val prover = new FakeProver(failed)
    prover.failConnect = true
    val thrown = intercept[RuntimeException](prover.start())
    assert(thrown.getMessage == "connect failed")
    assert(failed.destroyCount == 1)
    assert(prover.hook == null)

    val started = new FakeProcess
    prover.process = started
    prover.failConnect = false
    prover.start()
    assert(prover.hook != null)
    prover.stop()
    assert(started.destroyCount == 1)
    assert(failed.destroyCount == 1)
  }}

  /* Provers read the configuration of a started backend */
  private def withConfig(body: => Unit): Unit = {
    val backend = new Silicon(List("startedBy" -> s"Unit test ${this.getClass.getSimpleName}"))
    backend.parseCommandLine(List("--numberOfParallelVerifiers", "1", "dummy.sil"))
    backend.start()
    try body
    finally backend.stop()
  }

  private class FakeProcess extends Process {
    var destroyCount = 0
    var waitedUninterrupted = false

    def getOutputStream: OutputStream = OutputStream.nullOutputStream()
    def getInputStream: InputStream = InputStream.nullInputStream()
    def getErrorStream: InputStream = InputStream.nullInputStream()
    def waitFor(): Int = 0
    override def waitFor(timeout: Long, unit: TimeUnit): Boolean = {
      waitedUninterrupted = !Thread.currentThread().isInterrupted
      true
    }
    def exitValue(): Int = 0
    def destroy(): Unit = destroyCount += 1
    override def destroyForcibly(): Process = { destroy(); this }
    override def isAlive: Boolean = destroyCount == 0
  }

  /* A Z3 prover that talks to a fake process through writers that can be made to fail */
  private class FakeProver(var process: FakeProcess)
      extends Z3ProverStdIO("cleanup", new TermToSMTLib2Converter, new DefaultIdentifierFactory("cleanup"), NoopReporter) {
    var failConnect = false
    var outputClosed = false
    var inputClosed = false

    def hook: Thread = proverShutdownHook

    override protected def createProverInstance(userArgsString: Option[String]): Process = process

    override protected def connectToProver(): Unit = {
      if (failConnect) throw new RuntimeException("connect failed")
      connect(failFlush = false)
    }

    /* Puts the prover into a started state without a configured verifier */
    def install(failFlush: Boolean): Unit = {
      prover = process
      connect(failFlush)
      proverShutdownHook = new Thread
      Runtime.getRuntime.addShutdownHook(proverShutdownHook)
    }

    private def connect(failFlush: Boolean): Unit = {
      input = new BufferedReader(new StringReader("")) {
        override def close(): Unit = { inputClosed = true; super.close() }
      }
      output = new PrintWriter(new StringWriter) {
        override def flush(): Unit = if (failFlush) throw new RuntimeException("flush failed") else super.flush()
        override def close(): Unit = { outputClosed = true; super.close() }
      }
    }
  }
}
