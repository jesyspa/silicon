// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.supporters

import viper.silver.ast
import viper.silicon.state.Identifier
import viper.silicon.state.terms._

/** Recognises sufficient, unconditional equivalence laws in global domain axioms.
  * Every equivalence relation has a model extension R(x,y) = (class(x) = class(y)).
  * The class sort is fresh: equality of classes does not imply equality of arguments.
  * No axiom is removed, including constraints other than the three recognised laws.
  */
object EquivalenceClasses {
  case class Encoding(relation: DomainFun, classSort: Sort, classifier: Fun, definition: MacroDecl)

  /** Refuse state/substitution wrappers anywhere in a trigger, including around
    * the application. Extensions have no audited normalization contract.
    * Relation arguments are restricted to local variables: collection desugaring
    * and interpreted applications can also make source-distinct terms identical.
    * This is conservative, not a guarantee of E-matching completeness.
    */
  def unsupportedTriggerApplications(trigger: ast.Trigger): Seq[ast.DomainFuncApp] = {
    val normalizationSensitive = trigger.exists {
      case _: ast.OldExp | _: ast.Let | _: ast.Unfolding | _: ast.Applying | _: ast.ExtensionExp => true
      case _ => false
    }
    trigger.deepCollect {
      case a: ast.DomainFuncApp if normalizationSensitive ||
          a.args.exists(!_.isInstanceOf[ast.LocalVar]) ||
          (a.args.size == 2 && a.args.head == a.args(1)) => a
    }
  }

  private def conjuncts(t: Term): Seq[Term] = t match {
    case a: And => a.ts.flatMap(conjuncts)
    case _ => Seq(t)
  }

  /** Only direct universal conjuncts are inspected. In particular, implications,
    * existential binders and nested quantifiers are not traversed for laws.
    */
  def recognise(functions: Iterable[DomainFun], axioms: Iterable[Term]): Set[DomainFun] = {
    val eligible = functions.filter(f => f.argSorts.size == 2 &&
      f.argSorts.head == f.argSorts(1) && f.resultSort == sorts.Bool).toSet
    // Domain declarations use DomainFun, whereas translated domain applications use Fun.
    val declarations = eligible.map(f => (f.id, f.argSorts, f.resultSort) -> f).toMap
    val laws = scala.collection.mutable.Map.empty[DomainFun, Set[String]]
    def record(f: DomainFun, law: String): Unit =
      laws.update(f, laws.getOrElse(f, Set.empty) + law)

    axioms.toSeq.flatMap(conjuncts).foreach {
      case q: Quantification if q.q == Forall =>
        def edge(t: Term): Option[(DomainFun, Var, Var)] = t match {
          case App(f: Function, Seq(x: Var, y: Var))
            if q.vars.contains(x) && q.vars.contains(y) =>
            declarations.get((f.id, f.argSorts, f.resultSort)).map(d => (d, x, y))
          case _ => None
        }
        conjuncts(q.body).foreach { t =>
          edge(t).foreach { case (f, x, y) => if (x == y) record(f, "reflexive") }
          t match {
            case Equals(a, b) =>
              for ((f, x, y) <- edge(a); (g, u, v) <- edge(b))
                if (f == g && x != y && x == v && y == u) record(f, "symmetric")
            case Implies(Equals(x: Var, y: Var), result) =>
              edge(result).foreach { case (f, a, b) =>
                if (Set(x, y) == Set(a, b)) record(f, "reflexive")
              }
            case Implies(premise, result) =>
              val es = conjuncts(premise).map(edge)
              if (es.size == 2 && es.forall(_.isDefined)) {
                val Seq((f, a, b), (g, c, d)) = es.flatten
                edge(result).foreach { case (h, x, z) =>
                  if (f == g && g == h && x != z) {
                    if (a == x && b == c && d == z && Set(a, b, d).size == 3 ||
                        c == x && d == a && b == z && Set(c, d, b).size == 3)
                      record(f, "transitive")
                  }
                }
              }
              // A one-way universally quantified symmetry law is also sufficient.
              for ((f, x, y) <- edge(premise); (g, u, v) <- edge(result))
                if (f == g && x != y && x == v && y == u) record(f, "symmetric")
            case _ =>
          }
        }
      case _ =>
    }
    laws.collect { case (f, ls) if ls == Set("reflexive", "symmetric", "transitive") => f }.toSet
  }

  /** Generated names use a namespace unavailable to source identifiers. Check
    * occupied names as well, so instantiated domain declarations cannot collide.
    */
  def encode(relations: Iterable[DomainFun], occupiedNames: Set[String]): Seq[Encoding] = {
    val used = scala.collection.mutable.Set.empty[String] ++ occupiedNames
    var next = 0
    def fresh(stem: String): Identifier = {
      var name = ""
      do { name = s"$$equivalence@$stem@$next"; next += 1 } while (used(name))
      used += name
      Identifier(name)
    }
    relations.toSeq.sortBy(_.id.name).map { relation =>
      val classSort = sorts.UserSort(fresh("sort"))
      val classifier = Fun(fresh("class"), Seq(relation.argSorts.head), classSort)
      val x = Var(Identifier("x"), relation.argSorts.head, false)
      val y = Var(Identifier("y"), relation.argSorts.head, false)
      val definition = MacroDecl(relation.id, Seq(x, y), Equals(App(classifier, Seq(x)), App(classifier, Seq(y))))
      Encoding(relation, classSort, classifier, definition)
    }
  }
}
