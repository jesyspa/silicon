// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.rules

import org.scalatest.BeforeAndAfter
import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.{Config, Map}
import viper.silicon.state.Identifier
import viper.silicon.state.terms._
import viper.silicon.verifier.Verifier

/** These guards only construct terms; they do not start a prover. */
class QuantifiedPermissionSubstitutionTests extends AnyFunSuite with BeforeAndAfter {
  before {
    Verifier.config = new Config(Seq())
  }

  test("variable substitution is simultaneous, including structurally equal variables") {
    val x = Var(Identifier("x"), sorts.Int, false)
    val y = Var(Identifier("y"), sorts.Int, false)
    val sameX = Var(Identifier("x"), sorts.Int, false)
    val replacements = Map[Var, Term](x -> y, y -> x)
    val substitute = new QuantifiedPermissionSubstitution(replacements)
    val original = Minus(sameX, y)

    assert(substitute(original) == original.replace(replacements))
    assert(substitute(x) eq y)
    assert(substitute(y) eq x)
  }

  test("rewriting preserves quantifier metadata, triggers and permission simplifications") {
    val x = Var(Identifier("x"), sorts.Ref, false)
    val y = Var(Identifier("y"), sorts.Ref, false)
    val p = Var(Identifier("p"), sorts.Perm, false)
    val f = Fun(Identifier("f"), Seq(sorts.Ref), sorts.Perm)
    val lookup = App(f, Seq(x))
    val taken = Ite(x === y, PermMin(lookup, p), NoPerm)
    val body = PermAtMost(PermMinus(lookup, taken), FullPerm)
    val original = Forall(Seq(x), body, Seq(Trigger(lookup)), "qp-substitution", isGlobal = true)
    val replacements = Map[Var, Term](x -> y, p -> FullPerm)
    val substitute = new QuantifiedPermissionSubstitution(replacements)

    assert(substitute(original) == original.replace(replacements))
    assert(substitute(body) == body.replace(replacements))
  }

  test("a shared input DAG stays shared across roots without exponential expansion") {
    val x = Var(Identifier("x"), sorts.Perm, false)
    val y = Var(Identifier("y"), sorts.Perm, false)
    val f = Fun(Identifier("f"), Seq(sorts.Perm, sorts.Perm), sorts.Perm)
    val nodes = (1 to 40).scanLeft(x: Term)((child, _) => App(f, Seq(child, child)))
    val substitute = new QuantifiedPermissionSubstitution(Map[Var, Term](x -> y))

    var rewritten = substitute(nodes.last)
    assert(substitute(nodes.last) eq rewritten)
    for (depth <- 40 to 1 by -1) {
      val args = rewritten.asInstanceOf[App].args
      assert(args.head eq args(1))
      assert(substitute(nodes(depth - 1)) eq args.head)
      rewritten = args.head
    }
    assert(rewritten eq y)
  }

  test("empty and independent substitutions do not share mutable cache state") {
    val x = Var(Identifier("x"), sorts.Int, false)
    val y = Var(Identifier("y"), sorts.Int, false)
    val z = Var(Identifier("z"), sorts.Int, false)
    val original = Plus(x, IntLiteral(1))
    val empty = new QuantifiedPermissionSubstitution(Map.empty[Var, Term])
    val first = new QuantifiedPermissionSubstitution(Map[Var, Term](x -> y))
    val second = new QuantifiedPermissionSubstitution(Map[Var, Term](x -> z))

    assert(empty(original) eq original)
    assert(first(original) == Plus(y, IntLiteral(1)))
    assert(second(original) == Plus(z, IntLiteral(1)))
  }
}
