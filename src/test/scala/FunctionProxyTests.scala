// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.tests

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters._
import viper.silicon.Silicon
import viper.silver.ast.HasLineColumn
import viper.silver.verifier.Failure
import viper.silver.verifier.errors.AssertFailed

class FunctionProxyTests extends AnyFunSuite {
  for (mask <- Seq(false, true)) {
    test(s"Only live definition proxies are emitted after body verification (mask=$mask)") {
      val directory = Files.createTempDirectory("silicon-function-proxies")
      val backend = new Silicon(List("startedBy" -> getClass.getSimpleName))
      val frontend = new tests.DummyFrontend
      val options = Seq("--numberOfParallelVerifiers", "1", "--timeout", "30",
        "--proverLogFile", directory.resolve("solver").toString) ++
        (if (mask) Seq("--maskHeapMode") else Seq.empty)
      backend.parseCommandLine(options :+ "dummy.sil")
      try {
        try {
          backend.start()
          frontend.init(backend)
          val program = tests.loadProgram("functionProxies/", "liveness", frontend)
          tests.verifyProgram(program, frontend) match {
            case Failure(errors) =>
              assert(errors.size == 6)
              assert(errors.forall(_.isInstanceOf[AssertFailed]))
              val falseAssertions = program.methods.filter(_.name.startsWith("invalid"))
                .flatMap(_.body.toSeq.flatMap(_.ss)).map(_.pos.asInstanceOf[HasLineColumn].line).toSet
              assert(errors.map(_.pos.asInstanceOf[HasLineColumn].line).toSet == falseAssertions)
            case result => fail(s"Expected six false assertions, got $result")
          }
        } finally backend.stop()

        val files = Files.list(directory)
        val logs = try files.iterator.asScala.filter(_.toString.endsWith(".smt2")).toSeq
                   finally files.close()
        // Both the function prover and the method worker must receive live producers.
        assert(logs.size == 2)
        for (log <- logs) {
          val lines = Files.readAllLines(log).asScala.toVector
          val producers = lines.zipWithIndex.filter { case (line, _) =>
            line.startsWith("(assert") && line.contains("(! (") &&
              line.substring(line.indexOf("(! (") + 4).takeWhile(_ != ' ').contains("%stateless")
          }
          val expected = if (mask) Set("length") else Set("length", "fallback")
          val names = producers.map { case (line, _) =>
            line.substring(line.indexOf("(! (") + 4).takeWhile(_ != '%')
          }.toSet
          assert(names == expected, log.toString)
          assert(lines.count(line => line.startsWith("(declare-fun") && line.contains("%stateless")) == 8)
          for ((line, index) <- producers) {
            val name = line.substring(line.indexOf("(! (") + 4).takeWhile(_ != '%')
            // A producer immediately precedes its consumer, including in the worker preamble.
            assert(lines(index + 1).contains(s"($name%stateless"))
            val functionStart = lines.lastIndexWhere(_.contains(s" FUNCTION $name---"), index)
            if (functionStart >= 0) {
              assert(lines.slice(functionStart, index).exists(_.contains("Verification of function body")))
            }
          }
        }
      } finally {
        val files = Files.list(directory)
        try files.iterator.asScala.foreach(Files.delete(_)) finally files.close()
        Files.delete(directory)
      }
    }
  }
}
