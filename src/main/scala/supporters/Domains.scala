// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2019 ETH Zurich.

package viper.silicon.supporters

import viper.silver.ast
import com.typesafe.scalalogging.LazyLogging
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.common.collections.immutable.MultiMap._
import viper.silicon.toMap
import viper.silicon.interfaces.PreambleContributor
import viper.silicon.interfaces.decider.ProverLike
import viper.silicon.state.{FunctionPreconditionTransformer, SymbolConverter, terms}
import viper.silicon.state.terms.{Distinct, DomainFun, Sort, Term}
import viper.silver.ast.NamedDomainAxiom

trait DomainsContributor[SO, SY, AX, UA] extends PreambleContributor[SO, SY, AX] {
  def uniquenessAssumptionsAfterAnalysis: Iterable[UA]
  def emitUniquenessAssumptionsAfterAnalysis(sink: ProverLike): Unit
}

class DefaultDomainsContributor(symbolConverter: SymbolConverter,
                                domainTranslator: DomainsTranslator[Term],
                                equivalenceClasses: Boolean = false)
    extends DomainsContributor[Sort, DomainFun, Term, Term] with LazyLogging {

  private var collectedSorts = InsertionOrderedSet[Sort]()
  private var collectedFunctions = InsertionOrderedSet[terms.DomainFun]()
  private var collectedAxioms = InsertionOrderedSet[Term]()
  private var uniqueSymbols = MultiMap.empty[Sort, DomainFun]
  private var equivalences = Seq.empty[EquivalenceClasses.Encoding]

  /* Lifetime */

  def reset(): Unit = {
    collectedSorts = collectedSorts.empty
    collectedFunctions = collectedFunctions.empty
    collectedAxioms = collectedAxioms.empty
    uniqueSymbols = MultiMap.empty
    equivalences = Seq.empty
  }

  def start(): Unit = {}
  def stop(): Unit = {}

  /* Functionality */

  def analyze(program: ast.Program): Unit = {
    /* Compute necessary instances of all user-declared Viper domains */
    val necessaryDomainTypes = program.groundTypeInstances.collect{case d: ast.DomainType => d}

    /* A map from domain names to domain nodes */
    val domainNameMap = toMap(program.domains.map(d => d.name -> d))

    /* For each necessary domain type, instantiate the corresponding domain */
    val instantiatedDomains =
      necessaryDomainTypes map (cdt => domainNameMap(cdt.domainName).instantiate(cdt, program))

    collectDomainSorts(necessaryDomainTypes)
    collectDomainMembers(instantiatedDomains, program)
    if (equivalenceClasses) {
      val recognised = EquivalenceClasses.recognise(collectedFunctions, collectedAxioms)
      // A diagonal relation pattern expands to a reflexive equality. Z3 can
      // simplify it away and lose useful instantiations of unrelated formulas.
      val diagonal = program.deepCollect { case t: ast.Trigger =>
        t.deepCollect {
          case a: ast.DomainFuncApp if a.args.size == 2 && a.args.head == a.args(1) =>
            symbolConverter.toSortSpecificId(a.funcname, Seq(terms.sorts.Bool))
        }
      }.flatten.toSet
      val rejected = recognised.filter(f => diagonal(f.id))
      rejected.foreach(f => logger.warn(s"Equivalence-class encoding skipped ${f.id}: diagonal relation trigger is unsupported"))
      val relations = recognised -- rejected
      val occupied = collectedFunctions.map(_.id.name).toSet ++ collectedSorts.map(_.toString) ++
        program.deepCollect { case d: ast.Declaration => d.name }
      equivalences = EquivalenceClasses.encode(relations, occupied)
    }
  }

  private def collectDomainSorts(domainTypes: Iterable[ast.DomainType]): Unit = {
    assert(domainTypes forall (_.isConcrete), "Expected only concrete domain types")

    domainTypes.foreach(domainType => {
      val domainSort = symbolConverter.toSort(domainType)
      collectedSorts += domainSort
    })
  }

  private def collectDomainMembers(instantiatedDomains: Set[ast.Domain], program: ast.Program): Unit = {
    /* Since domain member instances come with Silver types, but the corresponding prover
     * declarations work with Silicon sorts, it can happen that two instances with different types
     * result in the same function declaration because the types are mapped to the same sort(s).
     *
     * Another source of potential declaration duplication is, that the set of domain member
     * instances can contain two function instances where the type variable mapping of one
     * instance is a subset of the mapping of the other. For example:
     *   function foo(a: A): Int    with (A -> Int)
     *   function foo(a: A): Int    with (A -> Int, B -> Ref)
     * This can happen if the declaring domain contains more type variables than are used by the
     * function.
     */

    instantiatedDomains foreach (domain => {
      domain.functions foreach (function => {
        if (function.interpretation.isEmpty) {
          val fct = symbolConverter.toFunction(function, program).asInstanceOf[DomainFun]

          collectedFunctions += fct

          if (function.unique) {
            assert(function.formalArgs.isEmpty,
              s"Expected unique domain functions to not take arguments, but found $function")

            uniqueSymbols = uniqueSymbols.addBinding(fct.resultSort, fct)
          }
        }
      })

      domain.axioms foreach (axiom => {
        val tAx = domainTranslator.translateAxiom(axiom, symbolConverter.toSort)
        val tAxPres = FunctionPreconditionTransformer.transform(tAx, program)
        collectedAxioms += terms.And(tAxPres, tAx)
      })
    })
  }

  def sortsAfterAnalysis: Iterable[terms.Sort] = collectedSorts

  def declareSortsAfterAnalysis(sink: ProverLike): Unit = {
    collectedSorts foreach (s => sink.declare(terms.SortDecl(s)))
    equivalences.foreach(e => sink.declare(terms.SortDecl(e.classSort)))
  }

  def symbolsAfterAnalysis: Iterable[terms.DomainFun] = collectedFunctions

  def declareSymbolsAfterAnalysis(sink: ProverLike): Unit = {
    if (equivalenceClasses)
      sink.comment(s"Equivalence-class encoding: ${equivalences.size} relations with all three global laws recognised")
    val replaced = equivalences.map(_.relation).toSet
    collectedFunctions.filterNot(replaced) foreach (f => sink.declare(terms.FunctionDecl(f)))
    equivalences.foreach { e =>
      sink.comment(s"Equivalence-class encoding: ${e.relation.id}; all domain axioms and trigger annotations retained")
      sink.declare(terms.FunctionDecl(e.classifier))
      sink.declare(e.definition)
    }
  }

  def axiomsAfterAnalysis: Iterable[terms.Term] = collectedAxioms

  def emitAxiomsAfterAnalysis(sink: ProverLike): Unit = {
    sink.assumeAxioms(collectedAxioms, "Domain axioms")
  }

  def uniquenessAssumptionsAfterAnalysis: Iterable[Term] =
    uniqueSymbols.map.values map Distinct

  def emitUniquenessAssumptionsAfterAnalysis(sink: ProverLike): Unit = {
    sink.assumeAxioms(InsertionOrderedSet(uniquenessAssumptionsAfterAnalysis), "Uniqueness axioms")
  }

  def updateGlobalStateAfterAnalysis(): Unit = { /* Nothing to contribute*/ }
}

trait DomainsTranslator[R] {
  def translateAxiom(ax: ast.DomainAxiom, toSort: ast.Type => Sort, builtin: Boolean = false): R
}

class DefaultDomainsTranslator()
    extends DomainsTranslator[Term]
       with ExpressionTranslator {

  def translateAxiom(ax: ast.DomainAxiom, toSort: ast.Type => Sort, builtin: Boolean = false): Term = {
    isBuiltin = builtin
    val res = translate(toSort)(ax.exp) match {
      case terms.Quantification(q, vars, body, triggers, "", _, weight) =>
        val qid = ax match {
          case axiom: NamedDomainAxiom => s"prog.${axiom.name}"
          case _ => ""
        }

        terms.Quantification(q, vars, body, triggers, qid, weight)

      case other => other
    }
    isBuiltin = false
    res
  }
}
