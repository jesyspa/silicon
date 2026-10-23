// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.decider

import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.state.terms._
import viper.silicon.verifier.Verifier

/** A private membership hash, not a replacement for Term.hashCode or equality.
  * Snapshot projections inherit their operand's hash. Distinguishing their paths
  * prevents long chains rooted at one snapshot from sharing a collision bucket.
  * ConditionalFlyweight equality requires equal classes and equality-defining
  * members, so mixing those discriminators preserves equal-implies-equal-hash.
  * Tuple members are combined in order; other values retain their existing hash.
  * The structural depth is bounded, but projection runs are traversed iteratively.
  */
private[decider] object PathConditionIndex {
  private def mix(a: Int, b: Int): Int = (a * 16777619) ^ b

  private def fingerprint(value: Any, depth: Int): Int = value match {
    case term: Term =>
      var rest = term
      var hash = 0
      var projecting = true
      while (projecting) rest match {
        case first: First => hash = mix(hash, 1); rest = first.p
        case second: Second => hash = mix(hash, 2); rest = second.p
        case _ => projecting = false
      }
      val base = if (depth == 0) rest.hashCode else rest match {
        case cf: ConditionalFlyweight[_, _] =>
          mix(rest.getClass.getName.hashCode, fingerprint(cf.equalityDefiningMembers, depth - 1))
        case _ => rest.hashCode
      }
      mix(hash, base)
    case (a, b) => mix(fingerprint(a, depth), fingerprint(b, depth))
    case (a, b, c) => mix(mix(fingerprint(a, depth), fingerprint(b, depth)), fingerprint(c, depth))
    case _ => value.##
  }

  private def indexHash(term: Term): Int =
    if (Verifier.config.useFlyweight) term.hashCode else fingerprint(term, 4)

  def empty: InsertionOrderedSet[Term] = InsertionOrderedSet.indexed(indexHash)

  def from(terms: IterableOnce[Term]): InsertionOrderedSet[Term] = empty ++ terms
}
