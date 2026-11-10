// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2022 ETH Zurich.

package viper.silicon.tests

import java.nio.file.Path

import viper.silicon.{Silicon, SiliconFrontend}
import viper.silver.reporter.NoopReporter
import viper.silver.testing.{DefaultAnnotatedTestInput, ExpectedOutput, LocatedAnnotation, MissingOutput, SilSuite, UnexpectedOutput}
import viper.silver.verifier.Verifier

class SiliconTests extends SilSuite {
  private val siliconTestDirectories =
    Seq("consistency")

  private val silTestDirectories =
    Seq("all",
        "quantifiedpermissions", "quantifiedpredicates", "quantifiedcombinations",
        "wands", "termination", "refute",
        "examples")

  val testDirectories: Seq[String] = siliconTestDirectories ++ silTestDirectories

  override def buildTestInput(file: Path, prefix: String): DefaultAnnotatedTestInput = {
    val input = DefaultAnnotatedTestInput(file, prefix)
    if (!java.lang.Boolean.getBoolean("silicon.exp.qpInverseSubst")) return input

    // Positive permission at null is contradictory; union membership implies membership in the union.
    // These tests record solver incompleteness when inverse applications hide those facts.
    val newlyProved = Map(
      "quantifiedpermissions/misc/unbounded.vpr" -> Set(32),
      "greedyQP/qp_unbounded.vpr" -> Set(32),
      "quantifiedpermissions/sets/nonnull.vpr" -> Set(45),
      "wands/new_syntax/QPFields.vpr" -> Set(16, 30, 41, 53, 66, 75, 87))
    val annotations = input.annotations.annotations.filterNot {
      case ann: ExpectedOutput => newlyProved.exists { case (path, lines) =>
        ann.file.toString.replace('\\', '/').endsWith(path) && lines(ann.forLineNr)
      }
      case ann: UnexpectedOutput => newlyProved.exists { case (path, lines) =>
        ann.file.toString.replace('\\', '/').endsWith(path) && lines(ann.forLineNr)
      }
      case _ => false
    }
    input.copy(annotations = input.annotations.copy(annotations = annotations))
  }

  override def frontend(verifier: Verifier, files: Seq[Path]): SiliconFrontend = {
    require(files.length == 1, "tests should consist of exactly one file")

    val fe = new SiliconFrontend(NoopReporter)
    fe.init(verifier)
    fe.reset(files.head)
    fe
  }

  override def annotationShouldLeadToTestCancel(ann: LocatedAnnotation): Boolean = {
    ann match {
      case UnexpectedOutput(_, _, _, _, _, _) => true
      case MissingOutput(_, _, _, _, _, issue) => issue != 34
      case _ => false
    }
  }

  val silicon: Silicon = {
    val reporter = NoopReporter
    val debugInfo = ("startedBy" -> "viper.silicon.SiliconTests") :: Nil
    new Silicon(reporter, debugInfo)
  }

  def verifiers: Seq[Silicon] = Seq(silicon)

  override def configureVerifiersFromConfigMap(configMap: Map[String, Any]): Unit = {
    val args = Silicon.optionsFromScalaTestConfigMap(prefixSpecificConfigMap(configMap).getOrElse("silicon", Map()))
    silicon.parseCommandLine(commandLineArguments ++ proverMemoryArguments(args) ++ args :+ Silicon.dummyInputFilename)
  }

  val commandLineArguments: Seq[String] =
    Seq("--timeout", "600" /* seconds */)

  /* A runaway Z3 query (e.g. a matching loop in linked-list-qp-append) grows by about 30 MB/s and
   * otherwise gets the whole run killed at 8 GB. With a cap Z3 reports out-of-memory, which Silicon
   * answers as a memory-out. Added here so subclasses need not repeat it, unless they or the
   * config map set --proverArgs themselves.
   */
  val proverMemoryMB = 3000

  private def proverMemoryArguments(configArgs: Seq[String]): Seq[String] =
    if ((commandLineArguments ++ configArgs).contains("--proverArgs")) Seq()
    else Seq("--proverArgs", s"memory_max_size=$proverMemoryMB")
}
