// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.decider

import scala.collection.mutable
import viper.silicon.state.terms.Term

/** Only for assertion results: membership must never suppress assumption emission. */
private[decider] final class SuccessfulProofCache(capacity: Int, enabled: Boolean = true) {
  require(capacity > 0)
  private val proofs = mutable.LinkedHashMap.empty[Term, Long]
  private var depth = 0L
  private var epoch = Long.MinValue
  private var _hits = 0L
  private var _queries = 0L
  def hits: Long = _hits
  def queries: Long = _queries

  def clear(): Unit = proofs.clear()

  def reset(): Unit = {
    clear()
    depth = 0L
    epoch = Long.MinValue
    _hits = 0L
    _queries = 0L
  }

  def push(): Unit = { depth += 1 }

  def pop(): Unit = {
    proofs.filterInPlace { case (_, scope) => scope < depth }
    depth -= 1
  }

  /** The caller supplies a monotonically changing prover-options epoch. */
  def prove(term: Term, currentEpoch: Long)(query: => Boolean): Boolean = {
    if (epoch != currentEpoch) {
      clear()
      epoch = currentEpoch
    }
    if (enabled && proofs.contains(term)) {
      _hits += 1
      true
    } else {
      _queries += 1
      val success = query
      if (enabled && success) {
        if (proofs.size >= capacity) proofs.remove(proofs.head._1)
        proofs.put(term, depth)
      }
      success
    }
  }
}
