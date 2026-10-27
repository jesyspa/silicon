// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.decider

import java.util.Properties

/** Experimental switches for ablating the contradiction skip and the fresh-prover retry. The defaults are
  * the normal behaviour:
  *   -Dsilicon.exp.retrySeed=same          retry with the seed of the first attempt (default: new)
  *   -Dsilicon.exp.retryFinal=unbounded    retry without a timeout (default: bounded, see
  *                                         [[ProverStdIO.RetryTimeoutFactor]])
  *   -Dsilicon.exp.contradictionSkip=false always query the prover in [[Decider.check]]
  */
final case class AssertRetryPolicy(newSeed: Boolean, boundedFinal: Boolean, contradictionSkip: Boolean)

object AssertRetryPolicy {
  def fromProperties(properties: Properties): AssertRetryPolicy = AssertRetryPolicy(
    newSeed = !"same".equals(properties.getProperty("silicon.exp.retrySeed")),
    boundedFinal = !"unbounded".equals(properties.getProperty("silicon.exp.retryFinal")),
    contradictionSkip = !"false".equals(properties.getProperty("silicon.exp.contradictionSkip")))

  /* Read when a prover or decider is created */
  def current: AssertRetryPolicy = fromProperties(System.getProperties)
}
