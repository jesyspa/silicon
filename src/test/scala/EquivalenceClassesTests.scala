// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Silicon
import viper.silicon.decider.TermToSMTLib2Converter
import viper.silicon.state.Identifier
import viper.silicon.state.terms._
import viper.silicon.supporters.EquivalenceClasses
import viper.silver.reporter.NoopReporter

class EquivalenceClassesTests extends AnyFunSuite {
  val silicon = new Silicon(NoopReporter, Nil)
  silicon.parseCommandLine(Seq("dummy.vpr"))
  silicon.start()

  val r = DomainFun(Identifier("relation"), Seq(sorts.Ref, sorts.Ref), sorts.Bool)
  val s = DomainFun(Identifier("other"), Seq(sorts.Ref, sorts.Ref), sorts.Bool)
  val x = Var(Identifier("x"), sorts.Ref, false)
  val y = Var(Identifier("y"), sorts.Ref, false)
  val z = Var(Identifier("z"), sorts.Ref, false)
  val guard = Var(Identifier("guard"), sorts.Bool, false)
  def edge(a: Term, b: Term, f: DomainFun = r): Term = App(Fun(f.id, f.argSorts, f.resultSort), Seq(a, b))
  def all(vs: Seq[Var], body: Term): Term = Forall(vs, body, Seq.empty[Trigger])
  val reflexive = all(Seq(x), edge(x, x))
  val symmetric = all(Seq(x, y), Equals(edge(x, y), edge(y, x)))
  val transitive = all(Seq(x, y, z), Implies(And(edge(x, y), edge(y, z)), edge(x, z)))
  val laws = Seq(reflexive, symmetric, transitive)
  def found(axioms: Seq[Term]): Set[DomainFun] = EquivalenceClasses.recognise(Seq(r, s), axioms)

  test("Recognise separate global laws and Nagini's combined reflexivity/symmetry law") {
    assert(found(laws) == Set(r))
    val combined = all(Seq(x, y), And(
      Equals(edge(x, y), edge(y, x)),
      Implies(Equals(x, y), edge(x, y)),
      Implies(Not(Equals(x, y)), Not(edge(x, y, s)))))
    assert(found(Seq(combined, transitive)) == Set(r))
    assert(found(Seq(And(laws))) == Set(r))
  }

  test("Every law is required; partial equivalence and preorder are not equivalence") {
    laws.indices.foreach(i => assert(found(laws.patch(i, Nil, 1)).isEmpty))
  }

  test("Conditional, existential and nested laws do not establish a global equivalence") {
    laws.indices.foreach { i =>
      assert(found(laws.updated(i, Implies(guard, laws(i)))).isEmpty)
    }
    assert(found(Seq(all(Seq(x), Implies(guard, edge(x, x))), symmetric, transitive)).isEmpty)
    assert(found(Seq(Exists(Seq(x), edge(x, x), Nil), symmetric, transitive)).isEmpty)
    assert(found(Seq(all(Seq(z), reflexive), symmetric, transitive)).isEmpty)
  }

  test("Free variables, extra premises and laws for different predicates are rejected") {
    assert(found(Seq(all(Seq(y), edge(x, x)), symmetric, transitive)).isEmpty)
    val guarded = all(Seq(x, y, z), Implies(And(edge(x, y), edge(y, z), guard), edge(x, z)))
    assert(found(Seq(reflexive, symmetric, guarded)).isEmpty)
    val mixed = all(Seq(x, y, z), Implies(And(edge(x, y), edge(y, z, s)), edge(x, z)))
    assert(found(Seq(reflexive, symmetric, mixed)).isEmpty)
  }

  test("Overloaded predicates cannot borrow laws from another homogeneous signature") {
    val ri = DomainFun(r.id, Seq(sorts.Int, sorts.Int), sorts.Bool)
    val xi = Var(x.id, sorts.Int, false)
    val yi = Var(y.id, sorts.Int, false)
    val zi = Var(z.id, sorts.Int, false)
    def ei(a: Term, b: Term): Term = edge(a, b, ri)
    val intLaws = Seq(
      all(Seq(xi), ei(xi, xi)),
      all(Seq(xi, yi), Equals(ei(xi, yi), ei(yi, xi))),
      all(Seq(xi, yi, zi), Implies(And(ei(xi, yi), ei(yi, zi)), ei(xi, zi))))
    laws.indices.foreach { i =>
      val split = laws.patch(i, Nil, 1) :+ intLaws(i)
      assert(EquivalenceClasses.recognise(Seq(r, ri), split).isEmpty)
    }
    assert(EquivalenceClasses.recognise(Seq(r, ri), laws ++ intLaws) == Set(r, ri))
    assert(found(Seq(all(Seq(xi), edge(x, x)), symmetric, transitive)).isEmpty)
  }

  test("Reversed join order and implication symmetry remain unconditional laws") {
    val reversed = all(Seq(x, y, z), Implies(And(edge(y, z), edge(x, y)), edge(x, z)))
    val symmetry = all(Seq(x, y), Implies(edge(x, y), edge(y, x)))
    assert(found(Seq(reflexive, symmetry, reversed)) == Set(r))
  }

  test("Trigger refusal covers nested and enclosing normalization wrappers") {
    import viper.silver.ast
    val a = ast.LocalVar("a", ast.Ref)()
    val b = ast.LocalVar("b", ast.Ref)()
    def rel(x: ast.Exp, y: ast.Exp) = ast.DomainFuncApp("rel", Seq(x, y), Map.empty)(
      ast.NoPosition, ast.NoInfo, ast.Bool, "Equiv", ast.NoTrafos)
    def rejected(e: ast.Exp) = EquivalenceClasses.unsupportedTriggerApplications(ast.Trigger(Seq(e))())
    assert(rejected(rel(a, b)).isEmpty)
    assert(rejected(rel(a, a)).nonEmpty)
    assert(rejected(rel(a, ast.Old(a)())).nonEmpty)
    assert(rejected(rel(a, ast.LabelledOld(a, "entry")())).nonEmpty)
    assert(rejected(ast.Old(rel(a, b))()).nonEmpty)
    val nested = ast.DomainFuncApp("wrap", Seq(ast.Old(a)()), Map.empty)(
      ast.NoPosition, ast.NoInfo, ast.Ref, "Equiv", ast.NoTrafos)
    assert(rejected(rel(a, nested)).exists(_.funcname == "rel"))
    val let = ast.Let(ast.LocalVarDecl("b", ast.Ref)(), a, b)()
    assert(rejected(rel(a, let)).nonEmpty)
    assert(rejected(rel(a, ast.NullLit()())).nonEmpty)
    val plainNested = nested.withArgs(Seq(a))
    assert(rejected(rel(a, plainNested)).exists(_.funcname == "rel"))
  }

  test("Only native counterexamples are supported with equivalence classes") {
    def config(mode: String, enabled: Boolean) = new viper.silicon.Config(
      Seq("--ignoreFile", "--exhaleMode", "1", "--counterexample", mode) ++
        (if (enabled) Seq("--equivalenceClasses") else Nil) :+ "dummy.vpr")
    Seq("variables", "mapped", "resolved", "raw").foreach { mode =>
      assert(config(mode, enabled = true).error.exists(_.contains("only native counterexamples")))
      assert(config(mode, enabled = false).error.isEmpty)
    }
    assert(config("native", enabled = true).error.isEmpty)
  }

  test("Class names avoid occupied symbols and definitions compare classes, not references") {
    val blocked = Set("$equivalence@sort@0", "$equivalence@class@2")
    val enc = EquivalenceClasses.encode(Seq(r, s), blocked)
    val names = enc.flatMap(e => Seq(e.classSort.asInstanceOf[sorts.UserSort].id.name, e.classifier.id.name))
    assert(names.distinct == names)
    assert(names.forall(n => !blocked(n)))
    val converter = new TermToSMTLib2Converter
    converter.start()
    enc.foreach { e =>
      assert(e.classSort != sorts.Ref)
      val declaration = converter.convert(e.definition)
      assert(declaration.contains(e.classifier.id.name))
      assert(!declaration.contains("(= x y)"))
    }
  }
}
