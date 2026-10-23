// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.decider

import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Silicon
import viper.silicon.state.Identifier
import viper.silicon.state.terms._
import viper.silver.reporter.NoopReporter

class TermToSMTLib2ConverterTests extends AnyFunSuite {
  /* Term equality depends on the verifier configuration */
  val silicon = new Silicon(NoopReporter, Nil)
  silicon.parseCommandLine(Seq("dummy.vpr"))
  silicon.start()

  val termConverter = new TermToSMTLib2Converter()
  termConverter.start()

  val p = Var(Identifier("p"), sorts.Perm, false)
  val q = Var(Identifier("q"), sorts.Perm, false)

  /* p, p - min(p, q), ... : the tree doubles at each level */
  def nested(base: Term, depth: Int): Term =
    (1 to depth).foldLeft(base)((t, _) => PermMinus(t, PermMin(t, q)))

  def occurrences(s: String, sub: String): Int = s.sliding(sub.length).count(_ == sub)

  test("Small terms are rendered without lets") {
    assert(termConverter.convert(nested(p, 2)) ==
      "(- (- p ($Perm.min p q)) ($Perm.min (- p ($Perm.min p q)) q))")
  }

  test("Repeated subterms of large terms are rendered once") {
    val rendered = termConverter.convert(nested(p, 30))
    assert(rendered.startsWith("(let ("))
    assert(occurrences(rendered, "$Perm.min") == 30)
  }

  test("The size precheck counts unfolded DAG occurrences and saturates before overflow") {
    // Only 162 distinct nodes, but more than Long.MaxValue unfolded occurrences.
    val rendered = termConverter.convert(nested(p, 80))
    assert(rendered.startsWith("(let ("))
    assert(occurrences(rendered, "$Perm.min") == 80)
  }

  test("The size precheck preserves structural sharing of distinct equal terms") {
    val left = nested(p, 13)
    val right = nested(p, 13)
    assert(left ne right)
    val shared = termConverter.convert(PermPlus(left, left))
    val copied = termConverter.convert(PermPlus(left, right))
    assert(shared.startsWith("(let ("))
    assert(copied == shared)
  }

  test("The precheck bounds deep-chain traversal without using the call stack") {
    val base = Var(Identifier("snapshot"), sorts.Snap, false)
    val deep = (1 to 100000).foldLeft[Term](base)((t, _) => new Second(t))
    assert(SharingAnalysis.mayHaveLargeTree(deep))
    // A binder is one leaf, regardless of the depth of its body.
    val binder = new Let(viper.silicon.Map(base -> base), deep)
    assert(!SharingAnalysis.mayHaveLargeTree(new Second(binder)))
  }

  test("Alias leaves defer conservatively without traversing their hidden children") {
    val base = Var(Identifier("snapshot"), sorts.Snap, false)
    val deep = (1 to 100000).foldLeft[Term](base)((t, _) => new Second(t))
    val heapSort = sorts.HeapSort(sorts.Int)
    val resource = viper.silver.ast.Field("resource", viper.silver.ast.Int)()
    val alias = new SnapToHeap(deep, resource, heapSort)
    alias.varRepr = Some(App(Fun(Identifier("alias"), Nil, heapSort), Nil))
    val at = Var(Identifier("at"), sorts.Ref, false)
    assert(SharingAnalysis.mayHaveLargeTree(new HeapLookup(alias, at)))
    assert(termConverter.convert(alias) == "(as alias $Hp<Int>)")

    // Alias state is deliberately absent from structural equality.
    val plain = new SnapToHeap(base, resource, heapSort)
    val named = new SnapToHeap(base, resource, heapSort)
    named.varRepr = alias.varRepr
    assert(plain == named)
    assert(SharingAnalysis.mayHaveLargeTree(new HeapLookup(named, at)))
    assert(termConverter.convert(new HeapLookup(named, at)) ==
      termConverter.convert(new HeapLookup(named.varRepr.get, at)))
  }

  test("The precheck retains the exact sharing threshold") {
    val base = Var(Identifier("snapshot"), sorts.Snap, false)
    val below = (1 until 9999).foldLeft[Term](base)((t, _) => new Second(t))
    assert(!SharingAnalysis.mayHaveLargeTree(below))
    assert(SharingAnalysis.mayHaveLargeTree(new Second(below)))
  }

  test("Subterms with bound variables are not shared across their binder") {
    val big = nested(p, 12)
    val body = PermLess(nested(Var(Identifier("x"), sorts.Perm, false), 12), big)
    val rendered = termConverter.convert(And(PermLess(NoPerm, big), Quantification(Forall, Seq(Var(Identifier("x"), sorts.Perm, false)), body, Nil)))
    val quantifier = rendered.substring(rendered.indexOf("(forall"))
    assert(rendered.startsWith("(let ("))
    assert(!quantifier.contains("$t!"))
    assert(occurrences(quantifier, "$Perm.min") == (1 << 13) - 2)
  }
}
