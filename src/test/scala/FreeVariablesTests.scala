// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.state.terms

import org.scalatest.BeforeAndAfter
import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.{Config, Map}
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.state.Identifier
import viper.silicon.verifier.Verifier

class FreeVariablesTests extends AnyFunSuite with BeforeAndAfter {
  before {
    Verifier.config = new Config(Seq())
  }

  private def variable(name: String): Var = new Var(Identifier(name), sorts.Int, false)

  // Preserve the tree reducer as an oracle, including its ordering and binder rules.
  private def treeFreeVariables(term: Term): InsertionOrderedSet[Var] =
    term.reduceTree { (t: Term, children: Seq[Set[Var]]) =>
      val variables = InsertionOrderedSet(children.flatten)
      t match {
        case q: Quantification => variables filterNot q.vars.contains
        case l: Let => variables diff l.bindings.keySet
        case v: Var => variables + v
        case _ => variables
      }
    }

  test("shared terms retain free-variable order across binders and triggers") {
    val x = variable("x")
    val y = variable("y")
    val z = variable("z")
    val f = new Fun(Identifier("f"), Seq(sorts.Int, sorts.Int), sorts.Bool)
    val shared = new App(f, Seq(x, y))
    val trigger = new App(f, Seq(z, x))
    val quantified = new Quantification(Forall, Seq(x), shared,
      Seq(Trigger(Seq(trigger))), "free-vars", false, None)
    val root = new And(Seq(quantified, shared))

    assert(root.freeVariables.toSeq == Seq(y, z, x))
    assert(root.freeVariables.toSeq == treeFreeVariables(root).toSeq)

    val let = new Let(Map(x -> y), shared)
    val nested = new And(Seq(let, quantified, shared, let))
    assert(nested.freeVariables.toSeq == treeFreeVariables(nested).toSeq)
  }

  test("structurally equal but distinct nodes preserve set equality and ordering") {
    val x = variable("x")
    val otherX = variable("x")
    val y = variable("y")
    val f = new Fun(Identifier("f"), Seq(sorts.Int, sorts.Int), sorts.Bool)
    val root = new And(Seq(new App(f, Seq(x, y)), new App(f, Seq(otherX, y))))

    assert(root.freeVariables.toSeq == Seq(x, y))
    assert(root.freeVariables.toSeq == treeFreeVariables(root).toSeq)
  }

  test("shared DAG edges are traversed a bounded number of times") {
    var reads = 0
    final class Arguments(child: Term)
        extends scala.collection.immutable.AbstractSeq[Term]
        with scala.collection.immutable.IndexedSeq[Term] {
      override def length: Int = 2
      override def apply(index: Int): Term = {
        require(index == 0 || index == 1)
        reads += 1
        child
      }
    }

    val x = variable("x")
    val f = new Fun(Identifier("f"), Seq(sorts.Int, sorts.Int), sorts.Int)
    val depth = 12
    for (leaf <- Seq[Term](x, new IntLiteral(0))) {
      var root = leaf
      for (_ <- 0 until depth) root = new App(f, new Arguments(root))
      reads = 0

      assert(root.freeVariables.toSeq == leaf.freeVariables.toSeq)
      assert(reads <= 8 * depth, s"Read $reads edges in a DAG of depth $depth")
    }
  }

  test("deep free-variable analysis does not use the call stack or term hashes") {
    val x = variable("x")
    val f = new Fun(Identifier("f"), Seq(sorts.Int), sorts.Int)
    var root: Term = x
    for (_ <- 0 until 20000) root = new App(f, Seq(root))

    assert(root.freeVariables.toSeq == Seq(x))
  }
}
