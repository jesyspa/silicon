// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.tests

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Silicon
import viper.silver.reporter.{InternalWarningMessage, Message, Reporter}
import viper.silver.verifier.Success

/** The feasibility checks of branches that are joined right away are optional: per member, only
  * -Dsilicon.exp.optionalCheckAllowance=N of them may fail with the full budget of a check.
  */
class OptionalCheckTests extends AnyFunSuite {
  /* The failed optional checks with the full budget, with the small budget, and the most in one member */
  private def failedOptionalChecks(allowance: Option[Int]): (Int, Int, Int) = {
    val warnings = mutable.ArrayBuffer[String]()
    val reporter = new Reporter {
      val name = "warnings"
      def report(msg: Message): Unit = msg match {
        case InternalWarningMessage(text) if text.contains("failed optional checks") => warnings += text
        case _ =>
      }
    }
    val properties = Seq(
      "silicon.exp.optionalCheckAllowance" -> allowance.map(_.toString),
      "silicon.exp.reportProverResources" -> Some("true"))
    val previous = properties.map { case (key, _) => key -> Option(System.getProperty(key)) }
    properties.foreach { case (key, value) => value.fold(System.clearProperty(key))(System.setProperty(key, _)) }
    try {
      val frontend = new tests.DummyFrontend
      val backend = new Silicon(reporter, List("startedBy" -> s"Unit test ${this.getClass.getSimpleName}"))
      backend.parseCommandLine(List("--numberOfParallelVerifiers", "1", "dummy.sil"))
      backend.start()
      frontend.init(backend)
      val program = tests.loadProgram("optionalChecks/", "joined", frontend)
      assert(tests.verifyProgram(program, frontend) == Success)
      backend.stop()
    } finally {
      previous.foreach { case (key, value) => value.fold(System.clearProperty(key))(System.setProperty(key, _)) }
    }
    val counts = warnings.toSeq.map { text =>
      val Seq(full, quick, most) = "\\d+".r.findAllIn(text.substring(text.indexOf("checks"))).map(_.toInt).toSeq
      (full, quick, most)
    }
    (counts.map(_._1).sum, counts.map(_._2).sum, counts.map(_._3).max)
  }

  test("A member that fails few optional checks keeps the full budget for all of them") {
    assert(failedOptionalChecks(None) == ((16, 0, 8)))
  }

  test("Without an allowance limit, every optional check gets the full budget") {
    assert(failedOptionalChecks(Some(Int.MaxValue)) == ((16, 0, 8)))
  }

  test("Each member may fail only as many optional checks with the full budget as the allowance permits") {
    assert(failedOptionalChecks(Some(3)) == ((6, 10, 8)))
  }

  test("An allowance of zero gives every optional check the small budget") {
    assert(failedOptionalChecks(Some(0)) == ((0, 16, 8)))
  }
}
