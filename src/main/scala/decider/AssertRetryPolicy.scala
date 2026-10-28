// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.decider

import java.util.Properties

/** Experimental switches for ablating the contradiction skip and the fresh-prover retry. The defaults are
  * the normal behaviour:
  *   -Dsilicon.exp.retrySeed=new           retry with a seed that differs from the first attempt's (default: same)
  *   -Dsilicon.exp.retryFinal=bounded      bound the retry at [[ProverStdIO.RetryTimeoutFactor]] times the first
  *                                         attempt's timeout (default: unbounded)
  *   -Dsilicon.exp.contradictionSkip=false always query the prover in [[Decider.check]]
  *   -Dsilicon.exp.retryFirstTimeout=800  first-attempt resource budget in equivalent milliseconds
  *   -Dsilicon.exp.retryFirstWall=2000    additionally bound the first attempt by wall milliseconds (Z3 rlimit mode)
  *   -Dsilicon.exp.retryProfile=true      report first-attempt timing and solver statistics
  */
final case class AssertRetryPolicy(newSeed: Boolean, boundedFinal: Boolean, contradictionSkip: Boolean,
                                   firstTimeout: Option[Int] = None, firstWall: Int = 0, profile: Boolean = false)

object AssertRetryPolicy {
  def fromProperties(properties: Properties): AssertRetryPolicy = AssertRetryPolicy(
    newSeed = "new".equals(properties.getProperty("silicon.exp.retrySeed")),
    boundedFinal = "bounded".equals(properties.getProperty("silicon.exp.retryFinal")),
    contradictionSkip = !"false".equals(properties.getProperty("silicon.exp.contradictionSkip")),
    firstTimeout = positiveInt(properties, "silicon.exp.retryFirstTimeout"),
    firstWall = positiveInt(properties, "silicon.exp.retryFirstWall").getOrElse(0),
    profile = "true".equals(properties.getProperty("silicon.exp.retryProfile")))

  private def positiveInt(properties: Properties, name: String): Option[Int] =
    Option(properties.getProperty(name)).map { value =>
      val parsed = value.toInt
      require(parsed > 0, s"$name must be positive")
      parsed
    }

  val default: AssertRetryPolicy = AssertRetryPolicy(newSeed = false, boundedFinal = false, contradictionSkip = true)

  /* Read when a prover or decider is created */
  def current: AssertRetryPolicy = fromProperties(System.getProperties)
}
