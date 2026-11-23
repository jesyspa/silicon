// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.decider

import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Config
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.state.Identifier
import viper.silicon.state.terms._
import viper.silicon.supporters.DefaultQuantifierSupporter
import viper.silicon.verifier.Verifier

class PathConditionAndFormulaTests extends AnyFunSuite {
  if (Verifier.config == null) {
    val cfg = new Config(Seq("dummy.vpr"))
    cfg.verify()
    Verifier.config = cfg
  }

  private def boolVar(name: String): Var = Var(Identifier(name), sorts.Bool, false)
  private def intVar(name: String): Var = Var(Identifier(name), sorts.Int, false)

  test("Popping an inner layer matches baseline removal when assumptions are re-added") {
    val a = boolVar("a")
    val b = boolVar("b")
    val c = boolVar("c")
    val pcs = new LayeredPathConditionStack()
    assert(pcs.isEmpty)

    pcs.add(a)
    pcs.add(c)
    pcs.pushScope()
    pcs.add(a)
    pcs.add(b)
    assert(pcs.contains(a) && pcs.contains(b) && pcs.contains(c))

    pcs.popScope()
    assert(!pcs.contains(a))
    assert(!pcs.contains(b))
    assert(pcs.contains(c))
    assert(pcs.assumptions.toSeq == Seq(c))
  }

  test("Mark array growth, after-slices, and duplicate stacks stay isolated") {
    val pcs = new LayeredPathConditionStack()
    val vars = (0 until 90).map(i => boolVar(s"v$i"))
    val marks = vars.map { v =>
      val m = pcs.mark()
      assert(!pcs.isEmpty)
      pcs.add(v)
      m
    }

    val copy = pcs.duplicate()
    val extra = boolVar("extra")
    copy.add(extra)
    assert(!pcs.contains(extra))
    assert(copy.contains(extra))

    assert(pcs.after(marks(85)).assumptions.toSeq == vars.drop(85).reverse)
    // Pop across several layers at once, then pop the remaining marks on a fresh stack to check isEmpty
    pcs.popUntilMark(marks(40))
    assert(pcs.contains(vars(39)))
    assert(!pcs.contains(vars(40)))
    assert(copy.contains(vars(89)))

    for (i <- 89 to 0 by -1) {
      if (i == 89) copy.add(extra)
      copy.popUntilMark(marks(i))
    }
    assert(copy.isEmpty)
  }

  test("Tautological auxiliary quantifier bodies are detected without perturbing general terms") {
    val p = boolVar("p")
    val q = boolVar("q")
    val r = boolVar("r")

    assert(BooleanSimplification.isTrivialTautology(Or(p, Not(p))))
    assert(BooleanSimplification.isTrivialTautology(Or(Not(p), p)))
    assert(BooleanSimplification.isTrivialTautology(Or(Seq(q, Not(p), p))))
    assert(BooleanSimplification.isTrivialTautology(Implies(And(p, q), q)))
    assert(BooleanSimplification.isTrivialTautology(Implies(And(p, q), And(Seq(p, Or(r, Not(r)))))))
    assert(!BooleanSimplification.isTrivialTautology(Implies(And(p, q), And(p, r))))
    assert(!BooleanSimplification.isTrivialTautology(Or(p, q)))
  }

  test("Heap-independent auxiliary triggers can be grouped and tautological bodies are dropped") {
    val supporter = new DefaultQuantifierSupporter(new TriggerGenerator())
    val x = intVar("x")
    val f = Fun(Identifier("f"), Seq(sorts.Int), sorts.Int)
    val g = Fun(Identifier("g"), Seq(sorts.Int), sorts.Int)
    val t1 = Trigger(Seq(App(f, Seq(x))))
    val t2 = Trigger(Seq(App(g, Seq(x))))
    val body = Greater((App(f, Seq(x)), IntLiteral(0)))

    var freshCount = 0
    def fresh(prefix: String, sort: Sort, @scala.annotation.unused ptype: Option[viper.silver.parser.PType]): Var = {
      freshCount += 1
      Var(Identifier(s"$prefix$freshCount"), sort, false)
    }

    val grouped = supporter.makeTriggersHeapIndependent(
      Quantification(Forall, Seq(x), body, Seq(t1, t2), "q-aux", isGlobal = false, weight = Some(5)),
      fresh
    )
    assert(freshCount == 0)
    assert(grouped.length == 1)
    assert(grouped.head.triggers == Seq(t1, t2))
    assert(grouped.head.weight.contains(5))

    val dropped = supporter.makeTriggersHeapIndependent(
      Quantification(Forall, Seq(x), Or(body, Not(body)), Seq(t1, t2), "q-true", isGlobal = false),
      fresh
    )
    assert(dropped.isEmpty)
    assert(freshCount == 0)
  }

  test("InsertionOrderedSet concat and removedAll preserve insertion order and share unchanged sets") {
    val s1 = InsertionOrderedSet(Seq(1, 2, 3))
    val s2 = InsertionOrderedSet(Seq(2, 4, 3, 5))

    assert((s1 ++ InsertionOrderedSet.empty[Int]) eq s1)
    assert((s1 ++ s1) eq s1)
    assert((InsertionOrderedSet.empty[Int] ++ s1) eq s1)
    assert((s1 ++ Seq(2, 3)) eq s1)

    val merged = s1 ++ s2
    assert(merged.toSeq == Seq(1, 2, 3, 4, 5))

    assert((s1 -- Seq(9, 10)) eq s1)
    val diff = merged -- Seq(2, 4)
    assert(diff.toSeq == Seq(1, 3, 5))
  }
}
