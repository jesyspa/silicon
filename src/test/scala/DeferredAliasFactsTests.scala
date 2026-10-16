// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.tests

import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Silicon
import viper.silver.reporter.NoopReporter
import viper.silver.verifier.{Failure, Success}

class DeferredAliasFactsTests extends AnyFunSuite {
  private val deferred = java.lang.Boolean.getBoolean("silicon.deferBasicAliasMerging")

  for ((mode, parallel) <- Seq(("0", false), ("1", false), ("0", true))) {
    test(s"persistent alias facts and their guards: exhale=$mode parallel=$parallel deferred=$deferred") {
      val backend = new Silicon(NoopReporter)
      val branchArgs = if (parallel) Seq("--parallelizeBranches") else Nil
      backend.parseCommandLine(Seq("--exhaleMode", mode, "--exhaleModeQP", "1",
        "--numberOfParallelVerifiers", (if (parallel) "2" else "1")) ++ branchArgs :+ Silicon.dummyInputFilename)
      backend.start()
      try {
        val frontend = new tests.DummyFrontend
        frontend.init(backend)
        val program = tests.loadProgram("deferredAliasFacts/", "persistent", frontend)
        val expected = Map(
          "separationAfterFold" -> (if (deferred) Nil else Seq("assert.failed:assertion.false")),
          "noSeparationFromHalves" -> Seq("assert.failed:assertion.false"),
          "guardedArrayAssignment" -> (if (deferred || mode == "1") Nil else Seq("assignment.failed:insufficient.permission")),
          "independentArrayAssignment" -> Seq("assignment.failed:insufficient.permission"))
        assert(program.methods.map(_.name).toSet == expected.keySet)
        program.methods.foreach { method =>
          val result = tests.verifyProgram(program.copy(methods = Seq(method))(program.pos, program.info, program.errT), frontend)
          val errors = result match {
            case Success => Nil
            case Failure(es) => es.map(_.fullId)
          }
          assert(errors == expected(method.name), s"${method.name}: $result")
        }
      } finally backend.stop()
    }
  }
}
