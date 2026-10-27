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

/** A check whose negation is assumed fails without a solver call, unless
  * -Dsilicon.exp.contradictionSkip=false.
  */
class ContradictionSkipTests extends AnyFunSuite {
  private def skipWarnings(property: Option[String]): Seq[String] = {
    val warnings = mutable.ArrayBuffer[String]()
    val reporter = new Reporter {
      val name = "warnings"
      def report(msg: Message): Unit = msg match {
        case InternalWarningMessage(text) if text.contains("contradicted checks") => warnings += text
        case _ =>
      }
    }
    val key = "silicon.exp.contradictionSkip"
    val previous = Option(System.getProperty(key))
    property.fold(System.clearProperty(key))(System.setProperty(key, _))
    try {
      val frontend = new tests.DummyFrontend
      val backend = new Silicon(reporter, List("startedBy" -> s"Unit test ${this.getClass.getSimpleName}"))
      backend.parseCommandLine(List("--numberOfParallelVerifiers", "1", "dummy.sil"))
      backend.start()
      frontend.init(backend)
      val program = tests.loadProgram("contradictionSkip/", "contradicted", frontend)
      assert(tests.verifyProgram(program, frontend) == Success)
      backend.stop()
    } finally {
      previous.fold(System.clearProperty(key))(System.setProperty(key, _))
    }
    warnings.toSeq
  }

  test("Contradicted checks are skipped by default") {
    assert(skipWarnings(None).nonEmpty)
  }

  test("-Dsilicon.exp.contradictionSkip=false disables the skip") {
    assert(skipWarnings(Some("false")).isEmpty)
  }
}
