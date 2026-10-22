// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.tests

import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Silicon
import viper.silicon.interfaces.SiliconFailureContext
import viper.silver.ast.HasLineColumn
import viper.silver.verifier.{Failure, VerificationError}
import viper.silver.verifier.errors.AssertFailed

/** Silicon restarts the prover when it runs out of memory and continues verifying. */
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
}
