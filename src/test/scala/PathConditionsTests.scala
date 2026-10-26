// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.decider

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Config
import viper.silicon.state.Identifier
import viper.silicon.state.terms._
import viper.silicon.verifier.Verifier

class PathConditionsTests extends AnyFunSuite with BeforeAndAfterEach {
  override protected def beforeEach(): Unit = {
    super.beforeEach()
    // Configure term equality and scope handling without starting a verifier or prover.
    Verifier.config = new Config(Seq())
  }

  private def bool(name: String): Var = Var(Identifier(name), sorts.Bool, false)

  test("popping a conjunction preserves conjuncts assumed in an outer scope and their order") {
    val pcs = new LayeredPathConditionStack
    val p = bool("p")
    val q = bool("q")
    val r = bool("r")
    pcs.add(p)
    pcs.add(q)
    val before = pcs.assumptions

    pcs.pushScope()
    pcs.add(And(p, r))
    pcs.addDefinition(q)
    pcs.popScope()

    assert(pcs.assumptions.toSeq == before.toSeq)
    assert(pcs.contains(p))
    assert(pcs.contains(q))
    assert(!pcs.contains(r))
    assert(pcs.definingAssumptions.isEmpty)
  }

  test("mark rollback restores the whole prefix while recorded deltas retain repeated terms") {
    val pcs = new LayeredPathConditionStack
    val p = bool("p")
    val q = bool("q")
    val r = bool("r")
    pcs.add(p)
    val mark = pcs.mark()
    pcs.add(And(p, q))
    val delta = pcs.after(mark)
    pcs.mark()
    pcs.add(And(q, r))
    pcs.popUntilMark(mark)

    assert(pcs.assumptions.toSeq == Seq(p))
    assert(delta.assumptions.toSet == Set(p, q))
    assert(delta.conditionalized == Seq(And(p, q)))

    pcs.pushScope()
    pcs.add(r)
    pcs.popScope()
    assert(pcs.assumptions.toSeq == Seq(p))
  }

  test("duplicate stacks can roll back the same mark independently") {
    val pcs = new LayeredPathConditionStack
    val p = bool("p")
    val q = bool("q")
    val r = bool("r")
    pcs.add(p)
    val mark = pcs.mark()
    pcs.add(And(p, q))
    val copy = pcs.duplicate()
    copy.add(r)
    pcs.popUntilMark(mark)

    assert(pcs.assumptions.toSeq == Seq(p))
    assert(copy.assumptions.toSeq == Seq(p, q, r))
    copy.popUntilMark(mark)
    assert(copy.assumptions.toSeq == Seq(p))
    copy.add(r)
    assert(pcs.assumptions.toSeq == Seq(p))
  }

  test("global assumptions and definitions survive duplicate occurrences in popped scopes") {
    val pcs = new LayeredPathConditionStack
    val p = bool("p")
    val global = Quantification(Forall, Seq(p), p, Nil, "global", isGlobal = true)
    pcs.addDefinition(global)
    pcs.pushScope()
    pcs.add(global)
    pcs.popScope()

    assert(pcs.contains(global))
    assert(pcs.assumptions.toSeq == Seq(global))
    assert(pcs.definingAssumptions.toSeq == Seq(global))
  }

  test("definition projection also projects rollback snapshots") {
    val pcs = new LayeredPathConditionStack
    val p = bool("p")
    val q = bool("q")
    val r = bool("r")
    pcs.add(p)
    pcs.addDefinition(q)
    val mark = pcs.mark()
    pcs.addDefinition(p)
    pcs.addDefinition(r)

    // The implementation keeps a stack when projecting, including its existing marks.
    val definitions = pcs.definitionsOnly.asInstanceOf[LayeredPathConditionStack]
    assert(definitions.assumptions.toSet == Set(p, q, r))
    definitions.popUntilMark(mark)
    assert(definitions.assumptions.toSeq == Seq(q))
    assert(!definitions.contains(p))
    assert(!definitions.contains(r))
    assert(pcs.assumptions.toSeq == Seq(p, q, r))
  }

  test("empty scopes and popping the initial scope preserve the aggregate invariant") {
    val pcs = new LayeredPathConditionStack
    val p = bool("p")
    pcs.add(p)
    pcs.pushScope()
    pcs.popScope()
    assert(pcs.assumptions.toSeq == Seq(p))
    pcs.popScope()
    assert(pcs.assumptions.isEmpty)
    assert(pcs.isEmpty)
    pcs.pushScope()
    pcs.add(p)
    assert(pcs.assumptions.toSeq == Seq(p))
  }
}
