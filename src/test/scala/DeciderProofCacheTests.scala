// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.decider

import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.{Config, Map}
import viper.silicon.state.terms._
import viper.silicon.verifier.{BaseVerifier, VerificationPoolManager, Verifier}
import viper.silver.ast
import viper.silver.reporter.NoopReporter

/** Exercises the Decider wiring with the configured SMT executable. */
class DeciderProofCacheTests extends AnyFunSuite {
  private def withDecider(test: Decider => Unit, args: Seq[String] = Seq()): Unit = {
    val config = new Config(args)
    Verifier.config = config
    val verifier = new BaseVerifier(config, "proof-cache-test") {
      val debugMode = false
      val reporter = NoopReporter
      def openSymbExLogger(member: ast.Member): Unit = ()
      def verificationPoolManager: VerificationPoolManager =
        throw new UnsupportedOperationException("No verification pool in Decider tests")
    }
    verifier.start()
    try {
      verifier.decider.prover.emit("(set-option :print-success true)")
      test(verifier.decider)
    } finally verifier.stop()
  }

  private def goal(d: Decider): Term = {
    val x = d.fresh("cache-x", sorts.Int, None)
    Greater(Plus(x, IntLiteral(1)), x)
  }

  private def queries(d: Decider): Long = d.statistics()("decider-proof-cache-queries").toLong
  private def hits(d: Decider): Long = d.statistics()("decider-proof-cache-hits").toLong

  test("repeated proofs, descendant reuse, and assumption emission") {
    withDecider { d =>
      val t = goal(d)
      assert(d.check(t, 10000))
      assert(d.check(t, 10000))
      assert(queries(d) == 1 && hits(d) == 1)
      d.pushScope()
      assert(d.check(t, 10000))
      d.popScope()
      assert(d.check(t, 10000))
      assert(queries(d) == 1 && hits(d) == 3)
      d.assume(t, None)
      assert(d.pcs.assumptions.contains(t))
    }
  }

  test("child proofs are discarded on pop and failures are retried") {
    withDecider { d =>
      val t = goal(d)
      d.pushScope()
      assert(d.check(t, 10000))
      d.popScope()
      assert(d.check(t, 10000))
      assert(queries(d) == 2)
      assert(!d.check(False, 10000))
      assert(!d.check(False, 10000))
      assert(queries(d) == 4)
    }
  }

  test("setPcs and both option APIs invalidate proofs") {
    withDecider { d =>
      val t = goal(d)
      assert(d.check(t, 10000))
      d.setPcs(d.pcs.duplicate())
      assert(d.check(t, 10000))
      assert(queries(d) == 2)
      d.setProverOptions(Map("smt.random_seed" -> "0"))
      assert(d.check(t, 10000))
      d.resetProverOptions()
      assert(d.check(t, 10000))
      val old = d.prover.setOption("smt.random_seed", "0")
      assert(d.check(t, 10000))
      d.prover.setOption("smt.random_seed", old)
      assert(queries(d) == 5)
    }
  }

  private val rawWeakening: Seq[(String, Decider => Unit)] = Seq(
    "pop" -> ((d: Decider) => d.prover.pop()),
    "emitted pop" -> ((d: Decider) => d.prover.emit("(pop 1)")),
    "reset-assertions" -> ((d: Decider) => d.prover.emit("(reset-assertions)")),
    "reset" -> ((d: Decider) => {
      d.prover.reset()
      d.prover.emit("(set-option :print-success true)")
    }))

  rawWeakening.foreach { case (name, weaken) =>
    test(s"raw prover $name invalidates proofs from a child-only contradiction") {
      withDecider { d =>
        d.prover.push()
        d.prover.assume(False)
        assert(d.check(False, 10000))
        assert(d.check(False, 10000))
        assert(queries(d) == 1 && hits(d) == 1)
        weaken(d)
        assert(!d.check(False, 10000))
        assert(queries(d) == 2)
      }
    }
  }

  Seq("pop", "reset").foreach { operation =>
    test(s"raw Z3 API $operation invalidates contradiction proofs") {
      withDecider(d => {
        d.prover.push()
        d.prover.assume(False)
        assert(d.check(False, 10000))
        assert(d.check(False, 10000))
        assert(queries(d) == 1 && hits(d) == 1)
        if (operation == "pop") d.prover.pop() else d.prover.reset()
        assert(!d.check(False, 10000))
        assert(queries(d) == 2)
      }, Seq("--prover", "Z3-API", viper.silicon.Silicon.dummyInputFilename))
    }
  }

  test("reset starts with an empty proof cache") {
    withDecider { d =>
      val t = goal(d)
      assert(d.check(t, 10000))
      d.asInstanceOf[viper.silver.components.StatefulComponent].reset()
      d.prover.emit("(set-option :print-success true)")
      assert(queries(d) == 0 && hits(d) == 0)
      assert(d.check(goal(d), 10000))
      assert(queries(d) == 1)
    }
  }
}
