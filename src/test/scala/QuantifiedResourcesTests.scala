// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.tests

import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.supporters.qps.QuantifiedResources
import viper.silver.ast._
import viper.silver.ast.utility.QuantifiedPermissions

class QuantifiedResourcesTests extends AnyFunSuite {
  private val x = LocalVarDecl("x", Ref)()
  private val a = Field("a", Ref)()
  private val b = Field("b", Ref)()
  private val c = Field("c", Ref)()
  private val p = Predicate("p", Seq(x), None)()

  private def access(f: Field) = FieldAccess(x.localVar, f)()
  private def pred(e: Exp) = PredicateAccess(Seq(e), p.name)()
  private def permission(f: Field) = FieldAccessPredicate(access(f), Some(FullPerm()()))()
  private def qPermission(f: Field) = Forall(Seq(x), Nil, permission(f))()
  private def method(name: String, pres: Seq[Exp], body: Stmt*) =
    Method(name, Nil, Nil, pres, Nil, Some(Seqn(body.toVector, Nil)()))(NoPosition, NoInfo, NoTrafos)
  private def call(name: String) = MethodCall(name, Nil, Nil)(NoPosition, NoInfo, NoTrafos)
  private def program(methods: Method*) = Program(Nil, Seq(a, b, c), Nil, Seq(p), methods.toVector, Nil)()

  test("member results retain Silver dependency order and exclude callee bodies") {
    val wand = MagicWand(permission(a), permission(b))()
    val qWand = Forall(Seq(x), Nil, wand)()
    val root = method("root", Nil, Assert(qPermission(b))(), call("left"), call("right"))
    val left = method("left", Seq(qPermission(a), qWand), call("root"), Assert(qPermission(c))())
    val right = method("right", Seq(Exists(Seq(x), Seq(Trigger(Seq(pred(access(c)), access(a)))()), TrueLit()())()))
    val prog = program(root, left, right)
    val analysis = new QuantifiedResources(prog)

    for (m <- prog.methods.reverse ++ prog.methods) {
      val actual = analysis.forMember(m)
      assert(actual.fields.toVector == QuantifiedPermissions.quantifiedFields(m, prog).toVector)
      assert(actual.predicates.toVector == QuantifiedPermissions.quantifiedPredicates(m, prog).toVector)
      assert(actual.triggers.toVector == QuantifiedPermissions.resourceTriggers(m, prog).toVector)
      assert(actual.wands.toVector == QuantifiedPermissions.quantifiedMagicWands(m, prog).toVector)
    }
    assert(analysis.forMember(root).fields.toVector == Vector(b, a))
    assert(analysis.forMember(root).wands.isEmpty)
    assert(analysis.forMember(left).wands.nonEmpty)
  }

  test("preamble follows nested trigger accesses; member analysis takes only top-level accesses") {
    val nested = pred(FieldAccess(access(c), b)())
    val exists = Exists(Seq(x), Seq(Trigger(Seq(nested))()), TrueLit()())()
    val forall = Forall(Seq(x), Seq(Trigger(Seq(access(c), pred(x.localVar)))()), permission(a))()
    val m = method("m", Seq(forall, exists))
    val prog = program(m)
    val analysis = new QuantifiedResources(prog)
    assert(analysis.preamble.fields.toVector == Vector(a, b, c))
    assert(analysis.preamble.predicates.toVector == Vector(p))
    val member = analysis.forMember(m)
    assert(member.fields.toVector == Vector(a))
    assert(member.predicates.toVector == Vector(p))
    assert(member.triggers.toVector == Vector(c, p))
  }

  test("wand groups retain Silver set iteration order before converting to identifiers") {
    val wands = for (l <- Vector(a, b, c); r <- Vector(a, b, c)) yield MagicWand(permission(l), permission(r))()
    val assertions = wands.zip(wands.reverse).map { case (body, trigger) =>
      Forall(Seq(x), Seq(Trigger(Seq(trigger))()), body)()
    }
    val trigOnly = Exists(Seq(x), Seq(Trigger(wands.take(2))()), TrueLit()())()
    val m = method("m", assertions :+ trigOnly)
    val prog = program(m)
    val analysis = new QuantifiedResources(prog)
    val expected = QuantifiedPermissions.quantifiedMagicWands(m, prog).toVector
    assert(expected.size > 4)
    assert(analysis.forMember(m).wands.toVector == expected)
    assert(analysis.preamble.wands.toVector == QuantifiedPermissions.quantifiedMagicWands(prog, prog).toVector)
  }
}
