// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2021 ETH Zurich.

package viper.silicon.decider

import java.nio.file.{Path, Paths}
import viper.silicon.state.IdentifierFactory
import viper.silicon.verifier.Verifier
import viper.silver.verifier.{DefaultDependency => SilDefaultDependency}
import viper.silver.reporter.Reporter
import viper.silicon.common.config.Version


object Z3ProverStdIO {
  val name = "Z3"
  val minVersion = Version("4.5.0")
  val maxVersion = None // Some(Version("4.5.0")) /* X.Y.Z if that is the *last supported* version */
  val exeEnvironmentalVariable = "Z3_EXE"
  val dependencies = Seq(SilDefaultDependency("Z3", minVersion.version, "https://github.com/Z3Prover/z3"))
  val staticPreamble = "/z3config.smt2"
  val startUpArgs = Seq("-smt2", "-in")
  val randomizeSeedsOptions = Seq("sat.random_seed", "nlsat.seed", "fp.spacer.random_seed", "smt.random_seed", "sls.random_seed")
}

class Z3ProverStdIO(uniqueId: String,
                    termConverter: TermToSMTLib2Converter,
                    identifierFactory: IdentifierFactory,
                    reporter: Reporter)
    extends ProverStdIO(uniqueId, termConverter, identifierFactory, reporter) {
    
  val name = Z3ProverStdIO.name
  val minVersion = Z3ProverStdIO.minVersion
  val maxVersion = Z3ProverStdIO.maxVersion
  val exeEnvironmentalVariable = Z3ProverStdIO.exeEnvironmentalVariable
  val dependencies = Z3ProverStdIO.dependencies
  val staticPreamble = Z3ProverStdIO.staticPreamble
  val startUpArgs = Z3ProverStdIO.startUpArgs
  val randomizeSeedsOptions = Z3ProverStdIO.randomizeSeedsOptions

  private val propositionalChecks = java.lang.Boolean.getBoolean("silicon.propositionalChecks")
  private val internalResources = 1000
  private var reducedBudget = false
  private var lastResourceBudget = -1
  private var internalBudgetScope = false
  private var currentPurpose = "obligation"
  private val queryTrace = java.lang.Boolean.getBoolean("silicon.queryTrace")
  private val queryCounters = java.lang.Boolean.getBoolean("silicon.queryCounters")
  private val queryResources = java.lang.Boolean.getBoolean("silicon.queryResources")
  private val totals = scala.collection.mutable.Map.empty[String, (Long, Long, Long, Long)]

  override def start(userArgsString: Option[String]): Unit = {
    reducedBudget = false
    internalBudgetScope = false
    currentPurpose = "obligation"
    lastResourceBudget = -1
    totals.clear()
    super.start(userArgsString)
  }

  override protected def assertUsingPushPop(goal: String, timeout: Option[Int]): (Boolean, Long) = {
    val before = if (queryResources) statistics().getOrElse("rlimit-count", "0").toLong else 0L
    val result = super.assertUsingPushPop(goal, timeout)
    if (queryCounters) {
      val after = if (queryResources) statistics().getOrElse("rlimit-count", "0").toLong else 0L
      if (queryTrace)
        println(s"QueryTrace\t$uniqueId\t$currentPurpose\t$reducedBudget\t${result._1}\t${result._2}\t${after - before}\t${goal.replaceAll("\\s+", " ")}")
      val kind = if (!internalBudgetScope) "obligation" else if (reducedBudget) "cheap" else "full"
      val (count, proved, millis, resources) = totals.getOrElse(kind, (0L, 0L, 0L, 0L))
      totals(kind) = (count + 1, proved + (if (result._1) 1 else 0),
        millis + result._2, resources + after - before)
    }
    result
  }

  override def stop(): Unit = {
    if (queryCounters && totals.nonEmpty)
      println(s"Query work $uniqueId: ${totals.toSeq.sortBy(_._1).mkString("; ")}")
    totals.clear()
    super.stop()
  }

  // Propositional probes retain the incremental engine and a positive budget.
  // Quantifier-backed predicates and semantic permission/alias queries retain
  // their original budgets. An unsuccessful probe is never reused as an answer.
  def withInternalBudget[T](purpose: String)(body: => T): T = {
    val previous = reducedBudget
    val previousScope = internalBudgetScope
    val previousPurpose = currentPurpose
    internalBudgetScope = true
    currentPurpose = purpose
    reducedBudget = propositionalChecks && purpose != "semantic"
    try body finally {
      reducedBudget = previous
      internalBudgetScope = previousScope
      currentPurpose = previousPurpose
    }
  }

  protected def setTimeout(timeout: Option[Int]): Unit = {
    val effectiveTimeout = timeout.getOrElse(Verifier.config.proverTimeout)
    val originalResources = effectiveTimeout * Verifier.config.z3ResourcesPerMillisecond()
    val resources = if (reducedBudget && originalResources > 0)
      math.min(originalResources, internalResources) else originalResources

    /* [2015-07-27 Malte] Setting the timeout unnecessarily often seems to
     * worsen performance, if only a bit. For the current test suite of
     * 199 Silver files, the total verification time increased from 60s
     * to 70s if 'set-option' is emitted every time.
     */
    if (lastTimeout != effectiveTimeout ||
        (!Verifier.config.proverEnableTimeBounds() && lastResourceBudget != resources)) {
      lastTimeout = effectiveTimeout

      if (!Verifier.config.proverEnableTimeBounds()) {
        lastResourceBudget = resources
        writeLine(s"(set-option :rlimit $resources)")
      } else {
        writeLine(s"(set-option :timeout $effectiveTimeout)")
      }
      readSuccess()
    }
  }

  protected def getProverPath: Path = {
    Paths.get(Verifier.config.z3Exe)
  }

  override def emitSettings(contents: Iterable[String]): Unit = {
    emit(contents)
    if (Verifier.config.disableNL.getOrElse(false)) {
      comment("We disable non-linear integer arithmetic")
      emit(s"(set-option :smt.arith.nl false)")
    }
  }
}
