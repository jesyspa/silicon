// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.tests

import java.io.PrintWriter
import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Silicon
import viper.silicon.decider.{AssertRetryPolicy, ProverStdIO, TermToSMTLib2Converter, Z3ProverStdIO}
import viper.silicon.interfaces.SiliconFailureContext
import viper.silicon.state.DefaultIdentifierFactory
import viper.silver.ast.HasLineColumn
import viper.silver.reporter.NoopReporter
import viper.silver.verifier.{Failure, VerificationError}
import viper.silver.verifier.errors.AssertFailed

/** Silicon restarts the prover when it runs out of memory and continues verifying, and when an assertion
  * reaches the timeout of its first attempt.
  */
class ProverMemoryOutTests extends AnyFunSuite {
  test("A prover memory-out fails the current assertion only") {
    val frontend = new tests.DummyFrontend
    val backend = new Silicon(List("startedBy" -> s"Unit test ${this.getClass.getSimpleName}"))
    backend.parseCommandLine(List("--numberOfParallelVerifiers", "1", "--proverArgs", "memory_max_size=50", "dummy.sil"))
    backend.start()
    frontend.init(backend)

    val program = tests.loadProgram("proverMemoryOut/", "memoryOut", frontend)
    val errors = tests.verifyProgram(program, frontend) match {
      case Failure(errors) => errors.collect { case e: VerificationError => e }
      case result => fail(s"Unexpected verification result: $result")
    }
    backend.stop()

    def reasonUnknown(e: VerificationError): Option[String] =
      e.failureContexts.collectFirst { case c: SiliconFailureContext => c.reasonUnknown }.flatten

    def line(e: VerificationError): Int = e.pos.asInstanceOf[HasLineColumn].line

    assert(errors.map(e => (e.isInstanceOf[AssertFailed], line(e), reasonUnknown(e))) == Seq(
      (true, 22, Some("out of memory")),
      (true, 29, None)))
  }

  /* A Z3 prover that answers the commands in [[failOn]] with a memory-out, and the commands in [[replace]]
   * with the given answers, after processing them
   */
  private class FailingProver(policy: AssertRetryPolicy = AssertRetryPolicy(newSeed = true, boundedFinal = true, contradictionSkip = true))
    extends Z3ProverStdIO("memoryOut", new TermToSMTLib2Converter,
                                                    new DefaultIdentifierFactory("memoryOut"), NoopReporter) {
    var failOn: List[String] = Nil
    var replace: List[(String, String)] = Nil
    var starts = 0
    /* The command-line arguments of each prover process */
    var startArgs: List[Option[String]] = Nil
    /* The commands sent to the prover, including replays */
    var sent: Vector[String] = Vector.empty
    private var lastSent: String = _

    override protected val retryPolicy: AssertRetryPolicy = policy

    def retryArgsFor(args: Option[String], retry: Int): Option[String] = retryArgs(args, retry)

    override protected def createProverInstance(userArgsString: Option[String]): Process = {
      startArgs = startArgs :+ userArgsString
      super.createProverInstance(userArgsString)
    }

    override protected def connectToProver(): Unit = {
      starts += 1
      super.connectToProver()
      output = new PrintWriter(output, true) {
        override def println(line: String): Unit = { lastSent = line; sent :+= line; super.println(line) }
      }
    }

    override protected def readLine(): String = {
      val answer = super.readLine()
      if (failOn.headOption.contains(lastSent)) {
        failOn = failOn.tail
        "(error \"out of memory\")"
      } else if (replace.headOption.exists(_._1 == lastSent)) {
        val replacement = replace.head._2
        replace = replace.tail
        replacement
      } else {
        answer
      }
    }
  }

  private def withProver(body: FailingProver => Unit): Unit = withProver(new FailingProver)(body)

  private def withProver(newProver: => FailingProver)(body: FailingProver => Unit): Unit = {
    val backend = new Silicon(List("startedBy" -> s"Unit test ${this.getClass.getSimpleName}"))
    backend.parseCommandLine(List("--numberOfParallelVerifiers", "1", "dummy.sil"))
    backend.start()
    val prover = newProver
    prover.start()
    /* Part of the preamble that Silicon would otherwise emit */
    prover.emit("(set-option :print-success true)")
    try body(prover)
    finally {
      prover.stop()
      backend.stop()
    }
  }

  test("A prover memory-out on a command other than check-sat loses no command") {
    withProver { prover =>
      prover.emit("(declare-const x Int)")
      /* Z3 rejects a duplicate declaration, so the command must be sent exactly once after the restart */
      prover.failOn = List("(declare-const y Int)")
      prover.emit("(declare-const y Int)")

      /* The next failed check is attributed to the memory-out, later ones are not */
      assert(!prover.assert("(= x y)", None))
      assert(prover.ranOutOfMemory())
      assert(prover.getReasonUnknown() == "out of memory")
      assert(!prover.assert("(= x y)", None))
      assert(!prover.ranOutOfMemory())

      prover.failOn = List("(assert (> x y))")
      prover.assume("(> x y)")
      assert(prover.assert("(> x y)", None))

      prover.failOn = List("(push) ; 1")
      prover.push()
      prover.assume("(> y 0)")
      assert(prover.assert("(> x 0)", None))
      prover.pop()
      assert(!prover.assert("(> x 0)", None))
    }
  }

  test("A prover that runs out of memory again after a restart is suspended until the scope is popped") {
    withProver { prover =>
      prover.emit("(declare-const x Int)")
      prover.push()
      prover.assume("(> x 0)")
      /* The command and its resending after the restart run out of memory */
      prover.failOn = List("(push) ; 2", "(push) ; 2")
      prover.push()
      assert(prover.failOn.isEmpty)

      assert(!prover.assert("(> x 0)", None))
      assert(prover.ranOutOfMemory())
      prover.pop()
      assert(!prover.assert("(> x 0)", None))
      assert(prover.ranOutOfMemory())

      prover.pop()
      prover.assume("(> x 1)")
      assert(prover.assert("(> x 0)", None))
      assert(!prover.assert("(> x 2)", None))
      assert(!prover.ranOutOfMemory())
    }
  }

  test("An assertion that reaches the timeout of its first attempt is retried in a fresh prover with another seed") {
    withProver { prover =>
      prover.emit("(declare-const x Int)")
      prover.assume("(> x 1)")
      prover.replace = List(
        "(check-sat)" -> "unknown",
        "(get-info :reason-unknown)" -> "(:reason-unknown \"max. resource limit exceeded\")")
      assert(prover.assert("(> x 0)", None))
      assert(prover.replace.isEmpty)
      assert(prover.starts == 2)
      assert(prover.startArgs == List(None, Some("smt.random_seed=1 sat.random_seed=1")))
      assert(!prover.ranOutOfMemory())

      /* An attempt that fails for another reason is not retried */
      prover.replace = List("(check-sat)" -> "unknown")
      assert(!prover.assert("(> x 0)", None))
      assert(prover.starts == 2)
    }
  }

  private def retryTimingOut(prover: FailingProver): Unit = {
    prover.emit("(declare-const x Int)")
    prover.assume("(> x 1)")
    prover.replace = List(
      "(check-sat)" -> "unknown",
      "(get-info :reason-unknown)" -> "(:reason-unknown \"max. resource limit exceeded\")")
    prover.sent = Vector.empty
    assert(prover.assert("(> x 0)", None))
    assert(prover.starts == 2)
  }

  /* The resource limit in effect at each check-sat */
  private def retryRlimits(prover: FailingProver): Seq[Long] =
    prover.sent.foldLeft((Option.empty[Long], Vector.empty[Long])) {
      case ((_, checks), cmd) if cmd.startsWith("(set-option :rlimit ") =>
        (Some(cmd.stripPrefix("(set-option :rlimit ").stripSuffix(")").toLong), checks)
      case ((limit, checks), "(check-sat)") => (limit, checks :+ limit.get)
      case (state, _) => state
    }._2

  test("-Dsilicon.exp.retryFinal=bounded bounds a retry at a multiple of the first attempt's timeout") {
    withProver { prover =>
      retryTimingOut(prover)
      val limits = retryRlimits(prover)
      assert(limits.length == 2 && limits(0) > 0 && limits(1) == limits(0) * ProverStdIO.RetryTimeoutFactor)
    }
  }

  test("A retry has no timeout by default") {
    withProver(new FailingProver(AssertRetryPolicy.default)) { prover =>
      retryTimingOut(prover)
      val limits = retryRlimits(prover)
      assert(limits.length == 2 && limits(0) > 0 && limits(1) == 0)
    }
  }

  test("A retry uses the arguments of the first attempt by default") {
    withProver(new FailingProver(AssertRetryPolicy.default)) { prover =>
      retryTimingOut(prover)
      assert(prover.startArgs == List(None, None))
    }
  }

  test("The experimental retry switches are read from system properties") {
    def policy(settings: (String, String)*): AssertRetryPolicy = {
      val properties = new java.util.Properties
      settings.foreach { case (k, v) => properties.setProperty(k, v) }
      AssertRetryPolicy.fromProperties(properties)
    }
    assert(policy() == AssertRetryPolicy.default)
    assert(policy() == AssertRetryPolicy(newSeed = false, boundedFinal = false, contradictionSkip = true))
    assert(policy("silicon.exp.retrySeed" -> "new", "silicon.exp.retryFinal" -> "bounded",
                  "silicon.exp.contradictionSkip" -> "false") ==
      AssertRetryPolicy(newSeed = true, boundedFinal = true, contradictionSkip = false))
    assert(policy("silicon.exp.retrySeed" -> "same", "silicon.exp.retryFinal" -> "unbounded") == policy())
  }

  test("A retry seed differs from the seed of the first attempt") {
    val prover = new FailingProver
    assert(prover.retryArgsFor(Some("smt.random_seed=4 sat.random_seed=4 memory_max_size=3000"), 2) ==
      Some("smt.random_seed=4 sat.random_seed=4 memory_max_size=3000 smt.random_seed=6 sat.random_seed=6"))
    assert(prover.retryArgsFor(Some("memory_max_size=3000"), 1) ==
      Some("memory_max_size=3000 smt.random_seed=1 sat.random_seed=1"))
  }
}
