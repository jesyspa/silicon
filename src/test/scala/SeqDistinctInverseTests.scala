// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.tests

import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Config
import viper.silicon.decider.SeqDistinctInverse
import viper.silicon.state.Identifier
import viper.silicon.state.terms._
import viper.silicon.verifier.Verifier

class SeqDistinctInverseTests extends AnyFunSuite {
  Verifier.config = new Config(Seq())

  private val i = Var(Identifier("i"), sorts.Int, false)
  private val j = Var(Identifier("j"), sorts.Int, false)
  private val s = Var(Identifier("S"), sorts.Seq(sorts.Ref), false)
  private val t = Var(Identifier("T"), sorts.Seq(sorts.Ref), false)
  private val c = Var(Identifier("c"), sorts.Bool, false)
  private val inv = Fun(Identifier("inv"), Seq(sorts.Ref), sorts.Int)
  private val range = SeqRanged(IntLiteral(0), SeqLength(s))

  private def rewrite(term: Term): Term = SeqDistinctInverse.rewrite(term, (_, _) => inv)

  private def distinct(guard: Term, a: Term, b: Term, vars: Seq[Var] = Seq(i, j)): Quantification =
    Quantification(Forall, vars, Implies(guard, Not(BuiltinEquals(a, b))), Seq(Trigger(Seq(a, b))), "q")

  private def inverse(guard: Term): Quantification =
    Quantification(Forall, Seq(i), Implies(guard, BuiltinEquals(App(inv, Seq(SeqAt(s, i))), i)),
      Seq(Trigger(Seq(SeqAt(s, i)))), "q-inv", false, None)

  private val rangeBounds = And(AtMost(IntLiteral(0), i), Less(i, SeqLength(s)))

  test("Rewrites pairwise distinctness over an index range") {
    val q = distinct(And(SeqIn(range, i), And(SeqIn(range, j), Not(BuiltinEquals(i, j)))), SeqAt(s, i), SeqAt(s, j))
    assert(rewrite(q) == inverse(rangeBounds))
  }

  test("Accepts either inequality orientation and keeps guards on neither variable") {
    val q = distinct(And(c, SeqIn(range, j), SeqIn(range, i), Not(BuiltinEquals(j, i))), SeqAt(s, i), SeqAt(s, j))
    assert(rewrite(q) == inverse(And(c, rangeBounds)))
  }

  test("Rewrites under conjunctions and implication consequents only") {
    val q = distinct(And(SeqIn(range, i), SeqIn(range, j), Not(BuiltinEquals(i, j))), SeqAt(s, i), SeqAt(s, j))
    assert(rewrite(And(c, q)) == And(c, inverse(rangeBounds)))
    assert(rewrite(Implies(c, q)) == Implies(c, inverse(rangeBounds)))
    assert(rewrite(Implies(q, c)) == Implies(q, c))
    assert(rewrite(Not(q)) == Not(q))
  }

  test("Leaves other shapes unchanged") {
    val neq = Not(BuiltinEquals(i, j))
    val shapes = Seq(
      // asymmetric guards
      distinct(And(SeqIn(range, i), neq), SeqAt(s, i), SeqAt(s, j)),
      distinct(And(Less(i, j), SeqIn(range, i), SeqIn(range, j)), SeqAt(s, i), SeqAt(s, j)),
      // no i != j
      distinct(And(SeqIn(range, i), SeqIn(range, j)), SeqAt(s, i), SeqAt(s, j)),
      // different sequences
      distinct(And(SeqIn(range, i), SeqIn(range, j), neq), SeqAt(s, i), SeqAt(t, j)),
      // sequence depends on a bound variable
      distinct(And(SeqIn(range, i), SeqIn(range, j), neq), SeqAt(s, i), SeqAt(SeqDrop(s, i), j)),
      Quantification(Exists, Seq(i, j), Implies(neq, Not(BuiltinEquals(SeqAt(s, i), SeqAt(s, j)))), Seq()))
    shapes.foreach(q => assert(rewrite(q) == q, q))
  }
}
