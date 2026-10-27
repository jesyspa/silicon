// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.decider

import viper.silicon.state.terms._

/** On by default; disabled with -Dsilicon.exp.seqDistinctInverse=false.
  *
  * Rewrites an assumed distinctness quantifier over a whole sequence
  *   forall i, j :: 0 <= i < |s| && 0 <= j < |s| && i != j ==> s[i] != s[j]
  * (bounds also as i in [0..|s|)) into the equivalent inverse-function form
  *   forall i :: 0 <= i && i < |s| ==> inv(s[i]) == i
  * where inv is fresh for each assumption. The pairwise form has a multi-pattern {s[i], s[j]} that
  * fires once per pair of index terms; the inverse form fires once per term.
  *
  * The rewrite is applied only to the terms sent to the prover. Silicon's path conditions keep the
  * original form, so function axioms and replays into other provers never mention inv.
  */
object SeqDistinctInverse {
  val enabled: Boolean = !"false".equals(System.getProperty("silicon.exp.seqDistinctInverse"))
  private val log: Boolean = java.lang.Boolean.getBoolean("silicon.exp.seqDistinctInverse.log")

  /** Rewrites distinctness quantifiers in positive positions of `t`: conjuncts and implication
    * consequents. `freshInverse(from, to)` must declare a fresh function.
    */
  def rewrite(t: Term, freshInverse: (Sort, Sort) => Function): Term = t match {
    case And(ts) =>
      val rewritten = ts.map(rewrite(_, freshInverse))
      if (rewritten == ts) t else And(rewritten)
    case Implies(p0, p1) =>
      val rewritten = rewrite(p1, freshInverse)
      if (rewritten eq p1) t else Implies(p0, rewritten)
    case q: Quantification =>
      recognize(q) match {
        case Some((x, guard, s)) =>
          val inverse = freshInverse(s.sort.asInstanceOf[sorts.Seq].elementsSort, x.sort)
          val at = SeqAt(s, x)
          if (log) System.err.println(s"seqDistinctInverse: rewrote ${q.name}")
          Quantification(Forall, Seq(x), Implies(guard, BuiltinEquals(App(inverse, Seq(at)), x)),
            Seq(Trigger(Seq(at))), s"${q.name}-inv", false, q.weight)
        case None => t
      }
    case _ => t
  }

  private def conjuncts(t: Term): Seq[Term] = t match {
    case And(ts) => ts.flatMap(conjuncts)
    case True => Seq.empty
    case _ => Seq(t)
  }

  /* Bounds of x stated by a guard conjunct: Some(true -> a) for a <= x, Some(false -> b) for x < b. */
  private def bounds(g: Term, x: Var): Option[Set[(Boolean, Term)]] = g match {
    case SeqIn(SeqRanged(a, b), `x`) => Some(Set(true -> a, false -> b))
    case AtMost(a, `x`) => Some(Set(true -> a))
    case AtLeast(`x`, a) => Some(Set(true -> a))
    case Less(`x`, b) => Some(Set(false -> b))
    case Greater(b, `x`) => Some(Set(false -> b))
    case _ => None
  }

  /** Returns the index variable, the guard (in terms of that variable) and the sequence. */
  private def recognize(q: Quantification): Option[(Var, Term, Term)] = {
    if (q.q != Forall || q.vars.size != 2) return None
    val Seq(v0, v1) = q.vars
    if (v0.sort != v1.sort) return None

    q.body match {
      case Implies(guard, Not(BuiltinEquals(SeqAt(s, i0: Var), SeqAt(s1, i1: Var))))
          if s == s1 && Set(i0, i1) == Set(v0, v1) && !s.hasSubterm(v0) && !s.hasSubterm(v1) =>
        val (x, y) = (i0, i1)
        def isNeq(g: Term) = g == Not(BuiltinEquals(x, y)) || g == Not(BuiltinEquals(y, x))
        val gs = conjuncts(guard)
        if (gs.count(isNeq) != 1) return None
        val rest = gs.filterNot(isNeq)
        val left = rest.filter(g => g.hasSubterm(x) && !g.hasSubterm(y))
        val right = rest.filter(g => g.hasSubterm(y) && !g.hasSubterm(x))
        if (left.size + right.size != rest.size) return None
        if (left.toSet != right.map(_.replace(y, x)).toSet) return None
        /* Only distinctness of the whole sequence: on testTranspose.vpr, where the bounds are
         * 0 <= i < M * N, the inverse form is 3.4 times slower or more. The guard is emitted as
         * arithmetic bounds: with i in [0..|s|), Z3 still runs into the matching loop of the
         * predicate's next-chain conjunct on linked-list-qp-append.vpr. */
        val stated = left.map(bounds(_, x))
        if (stated.contains(None) || stated.flatten.flatten.toSet != Set(true -> IntLiteral(0), false -> SeqLength(s)))
          return None
        Some((x, And(AtMost(IntLiteral(0), x), Less(x, SeqLength(s))), s))
      case _ => None
    }
  }
}
