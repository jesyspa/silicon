// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2019 ETH Zurich.

package viper.silicon.decider

import com.typesafe.scalalogging.Logger
import viper.silicon.debugger.DebugExp
import viper.silicon._
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.dependencyAnalysis._
import viper.silicon.interfaces._
import viper.silicon.interfaces.decider._
import viper.silicon.logger.records.data.{DeciderAssertRecord, DeciderAssumeRecord, ProverAssertRecord}
import viper.silicon.state._
import viper.silicon.state.terms.{Term, _}
import viper.silicon.utils.ast.{extractPTypeFromExp, simplifyVariableName}
import viper.silicon.verifier.{Verifier, VerifierComponent}
import viper.silver.ast
import viper.silver.ast.{LocalVarWithVersion, NoPosition}
import viper.silver.components.StatefulComponent
import viper.silver.parser.{PKw, PPrimitiv, PReserved, PType}
import viper.silver.reporter.{ConfigurationConfirmation, InternalWarningMessage}
import viper.silver.verifier.{DependencyNotFoundError, Model}

import scala.collection.immutable.HashSet
import scala.reflect.{ClassTag, classTag}
import scala.collection.mutable

/*
 * Interfaces
 */

object Decider {
  /** The number of optional checks per member that may fail with the full budget of a check, see
    * [[Decider.checkOptional]]. A member that fails fewer optional checks is verified exactly as if such
    * checks always had the full budget, which matters because the prover's behaviour on later queries depends
    * on the queries that preceded them. Members that fail many optional checks - hundreds or thousands in
    * programs with large specifications - no longer spend most of their prover time on them.
    * Set with `-Dsilicon.exp.optionalCheckAllowance=N`.
    */
  def optionalCheckAllowance: Int = Integer.getInteger("silicon.exp.optionalCheckAllowance", 16)
}

trait Decider {
  def functionDecls: Set[FunctionDecl]

  def macroDecls: Vector[MacroDecl]
  def prover: Prover
  def setProverOptions(options: Map[String, String]): Unit
  def getProverOptions(): Map[String, String]
  def resetProverOptions(): Unit
  def createProver(proverName: String, userArgsString: Option[String]): Option[DependencyNotFoundError]

  def pcs: PathConditionStack

  def debugVariableTypes: Map[String, PType]

  def pushScope(): Unit
  def popScope(): Unit

  def checkSmoke(analysisInfos: AnalysisInfos, isAssert: Boolean = false): Boolean

  def setCurrentBranchCondition(t: Term, te: (ast.Exp, Option[ast.Exp]), analysisInfos: AnalysisInfos): Unit
  def setPathConditionMark(): Mark

  def finishDebugSubExp(description : String): Unit

  def startDebugSubExp(): Unit

  def debuggerAssume(terms: Iterable[Term], de: DebugExp)

  /** The interface for reporting to the analysis of this run, which does nothing if there is none. */
  def analysisHooks: AnalysisHooks

  /**
   * Whether dependencies are recorded for the member currently being verified. Always false if the analysis is not
   * enabled overall (Config#isDependencyAnalysisEnabled).
   */
  def isDependencyAnalysisEnabledForMember: Boolean

  def isPathMarkedInfeasible: Boolean
  def handleInfeasiblePath(hasAssertions: Boolean, hasAssumptions: Boolean, analysisInfos: AnalysisInfos): Unit

  def assume(t: Term, e: Option[ast.Exp], finalExp: Option[ast.Exp], analysisInfos: AnalysisInfos): Unit
  def assume(t: Term, debugExp: Option[DebugExp], analysisInfos: AnalysisInfos): Unit
  def assume(terms: Seq[Term], debugExps: Option[Seq[DebugExp]], analysisInfos: AnalysisInfos): Unit
  def assumeDefinition(terms: Iterable[Term], debugExp: Option[DebugExp], analysisInfos: AnalysisInfos): Unit
  def assumeDefinition(t: Term, debugExp: Option[DebugExp], analysisInfos: AnalysisInfos): Unit
  def assume(terms: Iterable[Term], debugExp: Option[DebugExp], enforceAssumption: Boolean, analysisInfos: AnalysisInfos): Unit

  def check(t: Term, timeout: Int, analysisInfos: AnalysisInfos): Boolean

  /** Like `check`, but also returns the id of the dependency analysis node of a successful check, if there is one. */
  def checkWithNodeId(t: Term, timeout: Int, analysisInfos: AnalysisInfos): (Boolean, Option[Int])

  /** Like [[check]], but with the small budget of [[Prover.assertQuickly]] */
  def checkQuickly(t: Term, analysisInfos: AnalysisInfos): Boolean

  /** Checks a fact that verification does not need, but that saves work if it holds, such as that a branch
    * is infeasible whose result is joined with that of the other branch right away. The prover usually
    * establishes such a fact quickly if at all, whereas a check that fails takes its whole budget. Per
    * member, only [[Decider.optionalCheckAllowance]] such checks may fail with the budget of `timeout`;
    * from then on they get the small budget of [[checkQuickly]].
    */
  def checkOptional(t: Term, timeout: Int, analysisInfos: AnalysisInfos): Boolean

  /** The number of optional checks that failed since the allowance was last restored, see [[checkOptional]] */
  def failedOptionalChecks: Int

  /** To be called before a member is verified, see [[checkOptional]]. A decider that takes over a branch
    * from another one continues with the number of failed checks that the other one had at the branch.
    */
  def restoreOptionalCheckAllowance(failedChecks: Int = 0): Unit

  /* TODO: Consider changing assert such that
   *         1. It passes State and Operations to the continuation
   *         2. The implementation reacts to a failing assertion by e.g. a state consolidation
   */
  def assert(t: Term, analysisInfos: AnalysisInfos)(Q: Boolean => VerificationResult): VerificationResult
  def assert(t: Term, analysisInfos: AnalysisInfos, timeout: Option[Int])(Q: Boolean => VerificationResult): VerificationResult

  def fresh(id: String, sort: Sort, ptype: Option[PType]): Var
  def fresh(id: String, argSorts: Seq[Sort], resultSort: Sort): Function
  def freshMacro(id: String, formalArgs: Seq[Var], body: Term): MacroDecl

  def fresh(sort: Sort, pType: Option[PType]): Var
  def fresh(v: ast.AbstractLocalVar): (Var, Option[ast.LocalVarWithVersion])
  def freshARP(id: String = "$k"): (Var, Term, Option[ast.LocalVarWithVersion])
  def appliedFresh(id: String, sort: Sort, appliedArgs: Seq[Term]): App

  def createAlias(t: Term, s: State): Term

  def generateModel(): Unit
  def getModel(): Model
  def clearModel(): Unit
  def hasModel(): Boolean
  def isModelValid(): Boolean

/* [BRANCH-PARALLELISATION] */
  // HashSets lead to non-deterministic order, but branch parallelization leads to highly non-deterministic prover
  // states anyway (and Scala does not seem to have efficient order-preserving sets). ListSets are significantly
  // slower, so this tradeoff seems worth it.
  def freshFunctions: Set[FunctionDecl]
  def freshMacros: Vector[MacroDecl]
  def declareAndRecordAsFreshFunctions(functions: Set[FunctionDecl]): Unit
  def declareAndRecordAsFreshMacros(functions: Seq[MacroDecl]): Unit
  def setPcs(other: PathConditionStack): Unit

  def statistics(): Map[String, String]

  def handleFailedAssertion(failedAssertion: Term, e: Option[ast.Exp], finalExp: Option[ast.Exp], analysisInfos: AnalysisInfos, assumeFailedAssertion: Boolean): Unit

  def defaultAnalysisInfos: AnalysisInfos
}

/*
 * Implementations
 */

trait DefaultDeciderProvider extends VerifierComponent { this: Verifier =>

  val debugMode: Boolean
  def logger: Logger
  def symbolConverter: SymbolConverter
  def termConverter: TermToSMTLib2Converter
    /* TODO: Bad design: this decider chooses which prover to instantiate,
     *       but relies on another component to provide a suitable TermConverter
     */
  def identifierFactory: IdentifierFactory

  def decider: AbstractDecider = DefaultDecider

  object DefaultDecider extends AbstractDecider {
    type P = Prover

    override protected def getProver(prover: String): Prover = prover match {
      case Z3ProverStdIO.name => new Z3ProverStdIO(uniqueId, termConverter, identifierFactory, reporter)
      case Cvc5ProverStdIO.name => new Cvc5ProverStdIO(uniqueId, termConverter, identifierFactory, reporter)
      case Z3ProverAPI.name => new Z3ProverAPI(uniqueId, new TermToZ3APIConverter(), identifierFactory, reporter, triggerGenerator)
      case prover =>
        val msg1 = s"Unknown prover '$prover' provided. Defaulting to ${Z3ProverStdIO.name}."
        logger warn msg1
        getProver(Z3ProverStdIO.name)
    }
  }

  trait AbstractDecider extends Decider with StatefulComponent {
    type P <: Prover

    protected var _prover: P = _
    protected var pathConditions: PathConditionStack = _

    private var _declaredFreshFunctions: Set[FunctionDecl] = _ /* [BRANCH-PARALLELISATION] */
    private var _declaredFreshMacros: Vector[MacroDecl] = _
    private var _declaredFreshMacroNames: Set[String] = _ /* contains names of _declaredFreshMacros for faster lookup */

    private var _proverOptions: Map[String, String] = Map.empty
    private var _proverResetOptions: Map[String, String] = Map.empty
    /* The number of checks that failed without a solver call because their negation is assumed */
    private var skippedChecks: Int = 0
    private val contradictionSkip: Boolean = AssertRetryPolicy.current.contradictionSkip
    private val _debuggerAssumedTerms: mutable.Set[Term] = mutable.Set.empty

    def functionDecls: Set[FunctionDecl] = _declaredFreshFunctions
    def macroDecls: Vector[MacroDecl] = _declaredFreshMacros

    def prover: P = _prover

    def pcs: PathConditionStack = pathConditions

    var debugVariableTypes : Map[String, PType] = Map.empty

    override def defaultAnalysisInfos: AnalysisInfos = NoopAnalysisInfos

    def analysisHooks: AnalysisHooks = NoAnalysisHooks
    def isDependencyAnalysisEnabledForMember: Boolean = false

    def setPcs(other: PathConditionStack) = {
      /* [BRANCH-PARALLELISATION] */
      pathConditions = other
      while (prover.pushPopScopeDepth > 1){
        prover.pop()
      }
      // TODO: Change interface to make the cast unnecessary?
      val layeredStack = other.asInstanceOf[LayeredPathConditionStack]
      layeredStack.layers.reverse.foreach(l => {
        l.assumptions foreach prover.assume
        prover.push(timeout = Verifier.config.pushTimeout.toOption)
      })
    }

    protected def getProver(prover: String): P

    def createProver(proverName: String, userArgsString: Option[String]): Option[DependencyNotFoundError] = {
      _prover = getProver(proverName)

      _prover.start(userArgsString) /* Cannot query prover version otherwise */

      val proverVersion = _prover.version()
      // One can pass some options. This allows to check whether they have been received.

      val path = prover match {
        case pio: ProverStdIO => pio.proverPath
        case _ => "No Path"
      }
      val msg = s"Using ${_prover.name} $proverVersion located at ${path}"
      reporter report ConfigurationConfirmation(msg)
      logger debug msg

      if (proverVersion < _prover.minVersion) {
        val msg1 = s"Expected at least ${_prover.name} version ${_prover.minVersion.version}, but found $proverVersion"
        reporter report InternalWarningMessage(msg1)
        logger warn msg1
      }

      if (_prover.maxVersion.fold(false)(_ < proverVersion)) {
        val msg1 = s"Silicon might not work with ${_prover.name} version $proverVersion, consider using ${_prover.maxVersion.get}"
        reporter report InternalWarningMessage(msg1)
        logger warn msg1
      }

      None
    }

    override def setProverOptions(options: Map[String, String]): Unit = {
      _proverOptions = _proverOptions ++ options
      val resetOptions = _proverOptions.map { case (k, v) => (k, _prover.setOption(k, v)) }
      _proverResetOptions = resetOptions ++ _proverResetOptions
    }

    override def getProverOptions(): Map[String, String] = _proverOptions

    override def resetProverOptions(): Unit = {
      _proverResetOptions.foreach { case (k, v) => _prover.setOption(k, v) }
      _proverResetOptions = Map.empty
      _proverOptions = Map.empty
    }

    /* Life cycle */

    def start(): Unit = {
      skippedChecks = 0
      pathConditions = new LayeredPathConditionStack()
      _declaredFreshFunctions = if (Verifier.config.parallelizeBranches()) HashSet.empty else InsertionOrderedSet.empty /* [BRANCH-PARALLELISATION] */
      _declaredFreshMacros = Vector.empty
      _declaredFreshMacroNames = HashSet.empty
      createProver(Verifier.config.prover(), Verifier.config.proverArgs)
    }

    def reset(): Unit = {
      _prover.reset()
      pathConditions = new LayeredPathConditionStack()
      _declaredFreshFunctions = if (Verifier.config.parallelizeBranches()) HashSet.empty else InsertionOrderedSet.empty /* [BRANCH-PARALLELISATION] */
      _declaredFreshMacros = Vector.empty
      _declaredFreshMacroNames = HashSet.empty
      _proverOptions = Map.empty
    }

    def stop(): Unit = {
      if (ProverStdIO.reportResources && optionalCheckStats != ((0, 0, 0))) {
        val msg = s"Decider $uniqueId failed optional checks $optionalCheckStats"
        reporter report InternalWarningMessage(msg)
        logger info msg
        optionalCheckStats = (0, 0, 0)
      }
      if (skippedChecks > 0) {
        val msg = s"Decider $uniqueId skipped $skippedChecks contradicted checks"
        reporter report InternalWarningMessage(msg)
        logger info msg
        skippedChecks = 0
      }
      if (_prover != null) _prover.stop()
    }

    /* Assumption scope handling */

    def pushScope(): Unit = {
      //val commentRecord = new CommentRecord("push", null, null)
      //val sepIdentifier = symbExLog.openScope(commentRecord)
      pathConditions.pushScope()
      _prover.push(timeout = Verifier.config.pushTimeout.toOption)
      //symbExLog.closeScope(sepIdentifier)
    }

    def popScope(): Unit = {
      //val commentRecord = new CommentRecord("pop", null, null)
      //val sepIdentifier = symbExLog.openScope(commentRecord)
      _prover.pop()
      pathConditions.popScope()
      //symbExLog.closeScope(sepIdentifier)
    }

    def setCurrentBranchCondition(t: Term, te: (ast.Exp, Option[ast.Exp]), analysisInfos: AnalysisInfos): Unit =
      if (!isPathMarkedInfeasible) {
      pathConditions.setCurrentBranchCondition(t, te)
      assume(t, Option.when(te._2.isDefined)(te._1), te._2, analysisInfos)
    }

    def setPathConditionMark(): Mark = pathConditions.mark()

    /* Assuming facts */

    def startDebugSubExp(): Unit = {
      if (debugMode) {
        pathConditions.startDebugSubExp()
      }
    }

    def finishDebugSubExp(description: String): Unit = {
      if (debugMode) {
        pathConditions.finishDebugSubExp(description)
      }
    }

    def isPathMarkedInfeasible: Boolean = Verifier.config.analyzeInfeasiblePaths && pcs.isPathMarkedInfeasible

    /**
     * Called by [[viper.silicon.rules.InfeasiblePath.canPathBeSkipped]] when a rule skips its usual work because the
     * current path is known to be infeasible, in order to record what the rule would have contributed to the analysis.
     * Does nothing here since infeasible paths are only handled if the dependency analysis is enabled; see
     * DependencyAnalysisAwareDecider.
     */
    def handleInfeasiblePath(hasAssertions: Boolean, hasAssumptions: Boolean, analysisInfos: AnalysisInfos): Unit = {}

    def addDebugExp(e: DebugExp): Unit = {
      if (debugMode) {
        pathConditions.addDebugExp(e)
      }
    }

    def assume(t: Term, e: Option[ast.Exp], finalExp: Option[ast.Exp], analysisInfos: AnalysisInfos): Unit = {
      if (finalExp.isDefined) {
        assume(assumptions=InsertionOrderedSet((t, Some(DebugExp.createInstance(e.get, finalExp.get)))), enforceAssumption = false,
          isDefinition = false, analysisInfos)
      } else {
        assume(assumptions=InsertionOrderedSet((t, None)), enforceAssumption = false, isDefinition = false, analysisInfos)
      }
    }

    def assume(t: Term, debugExp: Option[DebugExp], analysisInfos: AnalysisInfos): Unit = {
      assume(InsertionOrderedSet(Seq((t, debugExp))), enforceAssumption = false, isDefinition = false, analysisInfos)
    }

    def assumeDefinition(t: Term, debugExp: Option[DebugExp], analysisInfos: AnalysisInfos): Unit = {
      assume(InsertionOrderedSet(Seq((t, debugExp))), enforceAssumption=false, isDefinition=true, analysisInfos)
    }

    def assumeDefinition(terms: Iterable[Term], debugExp: Option[DebugExp], analysisInfos: AnalysisInfos): Unit = {
      val filteredTerms = terms filterNot isKnownToBeTrue

      if (debugMode && filteredTerms.nonEmpty) {
        addDebugExp(debugExp.get.withTerm(And(filteredTerms)))
      }

      if (filteredTerms.nonEmpty) assumeWithoutSmokeChecks(InsertionOrderedSet(filteredTerms), analysisInfos, isDefinition = true)
    }

    def assume(assumptions: InsertionOrderedSet[(Term, Option[DebugExp])], enforceAssumption: Boolean, isDefinition: Boolean, analysisInfos: AnalysisInfos): Unit = {
      val filteredAssumptions =
        if (enforceAssumption) assumptions
        else assumptions filterNot (a => isKnownToBeTrue(a._1))


      if (debugMode) {
        filteredAssumptions foreach (a => addDebugExp(a._2.get.withTerm(a._1)))
      }

      if (filteredAssumptions.nonEmpty) {
         assumeWithoutSmokeChecks(InsertionOrderedSet(filteredAssumptions.map(_._1)), analysisInfos, isDefinition=isDefinition)
      }
    }

    def assume(assumptions: Seq[Term], debugExps: Option[Seq[DebugExp]], analysisInfos: AnalysisInfos): Unit = {
      assumeWithoutSmokeChecks(InsertionOrderedSet(assumptions), analysisInfos)
      if (debugMode) {
        debugExps.get foreach (e => addDebugExp(e))
      }
    }

    def assume(terms: Iterable[Term], debugExp: Option[DebugExp], enforceAssumption: Boolean, analysisInfos: AnalysisInfos): Unit = {
      val filteredTerms =
        if (enforceAssumption) terms
        else terms filterNot isKnownToBeTrue

      if (filteredTerms.nonEmpty) {
        if (debugMode) {
          addDebugExp(debugExp.get.withTerm(And(filteredTerms)))
        }

        assumeWithoutSmokeChecks(InsertionOrderedSet(filteredTerms), analysisInfos)
      }
    }

    def debuggerAssume(terms: Iterable[Term], de: DebugExp) = {
      terms.foreach(t => {
        if (!_debuggerAssumedTerms.contains(t)) {
          _debuggerAssumedTerms += t
          prover.assume(t)
          //TODODELETEtermSources.put(t, de)
        }
      })
    }

    protected def assumeWithoutSmokeChecks(terms: InsertionOrderedSet[Term], analysisInfos: AnalysisInfos, isDefinition: Boolean = false): Unit =
      assumeWithoutSmokeChecksInternal(terms, isDefinition, _ => None)(t => prover.assume(proverTerm(t)))

    protected def assumeWithoutSmokeChecksInternal(terms: InsertionOrderedSet[Term], isDefinition: Boolean, nodeIds: Term => Option[Int])(proverAssume: Term => Unit): Unit =
      if (!isPathMarkedInfeasible) {
        val assumeRecord = new DeciderAssumeRecord(terms)
        val sepIdentifier = symbExLog.openScope(assumeRecord)

        /* Add terms to Silicon-managed path conditions */
        if (isDefinition) {
          terms foreach (t => pathConditions.addDefinition(t, nodeIds(t)))
        } else {
          terms foreach (t => pathConditions.add(t, nodeIds(t)))
        }

        /* Add terms to the prover's assumptions */
        terms foreach proverAssume

        symbExLog.closeScope(sepIdentifier)
      }

    /* The form in which an assumption is sent to the prover */
    protected def proverTerm(t: Term): Term =
      if (SeqDistinctInverse.enabled) SeqDistinctInverse.rewrite(t, (from, to) => fresh("seqInv", Seq(from), to))
      else t

    /* Asserting facts */

    def checkSmoke(analysisInfos: AnalysisInfos, isAssert: Boolean=false): Boolean = {
      checkSmokeInternal(isAssert)
    }

    protected def checkSmokeInternal(isAssert: Boolean): Boolean = {
      val timeout = if (isAssert) Verifier.config.assertTimeout.toOption else Verifier.config.checkTimeout.toOption
      val result = prover.check(timeout) == Unsat
      if(result) pcs.markPathInfeasible(true)
      result
    }

    def checkQuickly(t: Term, analysisInfos: AnalysisInfos): Boolean =
      check(t, None, quickly = true, analysisInfos)._1

    private val optionalCheckAllowance: Int = Decider.optionalCheckAllowance
    private var _failedOptionalChecks = 0
    private var proverAsked = false
    /* MEASUREMENT: failed optional checks with the full budget, with the small budget, and the most per member */
    private var optionalCheckStats = (0, 0, 0)

    def failedOptionalChecks: Int = _failedOptionalChecks

    def restoreOptionalCheckAllowance(failedChecks: Int = 0): Unit = _failedOptionalChecks = failedChecks

    def checkOptional(t: Term, timeout: Int, analysisInfos: AnalysisInfos): Boolean = {
      val full = _failedOptionalChecks < optionalCheckAllowance
      proverAsked = false
      val holds = if (full) check(t, timeout, analysisInfos) else checkQuickly(t, analysisInfos)
      if (!holds && proverAsked) {
        _failedOptionalChecks += 1
        val (fullFailed, quickFailed, most) = optionalCheckStats
        optionalCheckStats =
          (fullFailed + (if (full) 1 else 0), quickFailed + (if (full) 0 else 1), most.max(_failedOptionalChecks))
      }
      holds
    }

    private def check(t: Term, timeout: Option[Int], quickly: Boolean, analysisInfos: AnalysisInfos): (Boolean, Option[Int]) = {
      /* If the negation of t is assumed, then t holds only if the path conditions are inconsistent. A check
       * is allowed to fail in that case, which saves a costly query that usually ends in unknown. On a path
       * already marked infeasible, every check holds without a query, see [[proverAssert]].
       */
      val negated = t match {
        case Not(a) => a
        case _ => Not(t)
      }
      if (contradictionSkip && !isPathMarkedInfeasible && pcs.assumptions.contains(negated)) {
        skippedChecks += 1
        (false, None)
      } else {
        deciderAssert(t, analysisInfos, timeout, isCheck = true, quickly = quickly)
      }
    }

    override def handleFailedAssertion(failedAssertion: Term, e: Option[ast.Exp], finalExp: Option[ast.Exp], analysisInfos: AnalysisInfos, assumeFailedAssertion: Boolean): Unit = {
      if (assumeFailedAssertion) {
        assume(failedAssertion, e, finalExp, failedAssertionInfos(analysisInfos))
        failedAssertion match {
          case False => checkSmoke(analysisInfos)
          case _ =>
        }
      }
    }

    /* The infos to use when a failed assertion is subsequently assumed. */
    protected def failedAssertionInfos(analysisInfos: AnalysisInfos): AnalysisInfos = analysisInfos

    def check(t: Term, timeout: Int, analysisInfos: AnalysisInfos): Boolean =
      checkWithNodeId(t, timeout, analysisInfos)._1

    def checkWithNodeId(t: Term, timeout: Int, analysisInfos: AnalysisInfos): (Boolean, Option[Int]) =
      check(t, Some(timeout), quickly = false, analysisInfos)

    def assert(t: Term, analysisInfos: AnalysisInfos)(Q:  Boolean => VerificationResult): VerificationResult =
        assert(t, analysisInfos,  timeout=Verifier.config.assertTimeout.toOption)(Q)

    def assert(t: Term, analysisInfos: AnalysisInfos, timeout: Option[Int])(Q:  Boolean => VerificationResult): VerificationResult = {
      val (success, _) = deciderAssert(t, analysisInfos, timeout)

      // If the SMT query was not successful, store it (possibly "overwriting"
      // any previously saved query), otherwise discard any query we had saved
      // previously (it did not cause a verification failure) and ignore the
      // current one, because it cannot cause a verification error.
      if (success)
        symbExLog.discardSMTQuery()
      else
        symbExLog.setSMTQuery(t)

      Q(success)
    }

    private def deciderAssert(t: Term, analysisInfos: AnalysisInfos, timeout: Option[Int], isCheck: Boolean = false, quickly: Boolean = false): (Boolean, Option[Int]) = {
      val assertRecord = new DeciderAssertRecord(t, timeout)
      val sepIdentifier = symbExLog.openScope(assertRecord)

      val result = if (isKnownToBeTrue(t)) (true, None) else deciderAssertInternal(t, timeout, analysisInfos, isCheck, quickly)

      symbExLog.closeScope(sepIdentifier)
      result
    }

    protected def deciderAssertInternal(t: Term, timeout: Option[Int], analysisInfos: AnalysisInfos, isCheck: Boolean, quickly: Boolean): (Boolean, Option[Int]) =
      (proverAssert(t, timeout)(if (quickly) prover.assertQuickly(t) else prover.assert(t, timeout)), None)

    protected def isKnownToBeTrue(t: Term) = t match {
      case True => true
  //    case eq: BuiltinEquals => eq.p0 == eq.p1 /* WARNING: Blocking trivial equalities might hinder axiom triggering. */
      case _ if pcs.assumptions contains t => true
      case q: Quantification if q.body == True => true
      case _ => false
    }

    protected def proverAssert(t: Term, timeout: Option[Int])(invokeProver: => Boolean): Boolean = {
      val assertRecord = new ProverAssertRecord(t, timeout)
      val sepIdentifier = symbExLog.openScope(assertRecord)

      val result = isPathMarkedInfeasible || { proverAsked = true; invokeProver }

      symbExLog.whenEnabled {
        assertRecord.statistics = Some(symbExLog.deltaStatistics(prover.statistics()))
      }

      symbExLog.closeScope(sepIdentifier)

      result
    }

    /* Fresh symbols */

    def fresh(id: String, argSorts: Seq[Sort], resultSort: Sort): Function =
      prover_fresh[Fun](id, argSorts, resultSort, false)

    def fresh(id: String, sort: Sort, pType: Option[PType]): Var = {
      val term = prover_fresh[Var](id, Nil, sort, false)
      if (debugMode) debugVariableTypes += (term.id.name -> pType.get)
      term
    }

    def fresh(s: Sort, pType: Option[PType]): Var = {
      val term = prover_fresh[Var]("$t", Nil, s, false)
      if (debugMode) debugVariableTypes += (term.id.name -> pType.get)
      term
    }

    def fresh(v: ast.AbstractLocalVar): (Var, Option[ast.LocalVarWithVersion]) = {
      val withExp = debugMode
      val term = fresh(v.name, symbolConverter.toSort(v.typ), Option.when(withExp)(extractPTypeFromExp(v)))
      (term, Option.when(withExp)(LocalVarWithVersion(simplifyVariableName(term.id.name), v.typ)(v.pos, v.info, v.errT)))
    }

    def freshARP(id: String = "$k"): (Var, Term, Option[ast.LocalVarWithVersion]) = {
      val permVar = prover_fresh[Var](id, Nil, sorts.Perm, true)
      val permVarConstraints = IsReadPermVar(permVar)
      if (debugMode) debugVariableTypes += (permVar.id.name -> PPrimitiv(PReserved(PKw.Perm)((NoPosition, NoPosition)))())
      (permVar, permVarConstraints, Option.when(debugMode)(LocalVarWithVersion(simplifyVariableName(permVar.id.name), ast.Perm)()))
    }

    def freshMacro(id: String, formalArgs: Seq[Var], body: Term): MacroDecl = {
      val name = identifierFactory.fresh(id)
      val macroDecl = MacroDecl(name, formalArgs, body)

      prover.declare(macroDecl)

      _declaredFreshMacros = _declaredFreshMacros :+ macroDecl /* [BRANCH-PARALLELISATION] */
      _declaredFreshMacroNames = _declaredFreshMacroNames + name.name

      macroDecl
    }

    def appliedFresh(id: String, sort: Sort, appliedArgs: Seq[Term]): App = {
      val appliedSorts = appliedArgs.map(_.sort)
      val func = fresh(id, appliedSorts, sort)

      App(func, appliedArgs)
    }

    private def prover_fresh[F <: Function : ClassTag]
                            (id: String, argSorts: Seq[Sort], resultSort: Sort, isARP: Boolean)
                            : F = {
//      context.bookkeeper.freshSymbols += 1

      val proverFun = prover.fresh(id, argSorts, resultSort)

      val destClass = classTag[F].runtimeClass

      val fun: F =
        if (proverFun.getClass == destClass)
          proverFun.asInstanceOf[F]
        else
          destClass match {
            case c if c == classOf[Var] =>
              Predef.assert(proverFun.argSorts.isEmpty)
              Var(proverFun.id, proverFun.resultSort, isARP).asInstanceOf[F]
            case c if c == classOf[Fun] => proverFun.asInstanceOf[F]
            case c if c == classOf[DomainFun] =>
              DomainFun(proverFun.id, proverFun.argSorts, proverFun.resultSort).asInstanceOf[F]
            case c if c == classOf[HeapDepFun] =>
              HeapDepFun(proverFun.id, proverFun.argSorts, proverFun.resultSort).asInstanceOf[F]
          }

      val decl = FunctionDecl(fun)
      _declaredFreshFunctions = _declaredFreshFunctions + decl /* [BRANCH-PARALLELISATION] */

      fun
    }

    def createAlias(t: Term, s: State): Term = {
      t match {
        /* No aliases are introduced while a wand is being packaged (packagingWandSnapshots
         * is non-empty): terms may then contain the wand's snapshot root, which ends up as a
         * quantified variable in the MWSF definition. A macro whose body mentions the root
         * would refer to the global constant rather than the bound variable. */
        case hvr: HasVarRepr if hvr.varRepr.isEmpty && s.quantifiedVariables.isEmpty && s.packagingWandSnapshots.isEmpty && s.isMethodVerification =>
          val md = freshMacro("tmpTerm", Seq(), t)
          val mcr = Macro(md.id, Seq(), md.body.sort)
          hvr.varRepr = Some(App(mcr, Seq()))
          t
        case _ => t
      }
    }


/* [BRANCH-PARALLELISATION] */
    def freshFunctions: Set[FunctionDecl] = _declaredFreshFunctions
    def freshMacros: Vector[MacroDecl] = _declaredFreshMacros

    def declareAndRecordAsFreshFunctions(functions: Set[FunctionDecl]): Unit = {
      for (f <- functions) {
        if (!_declaredFreshFunctions.contains(f)) {
          prover.declare(f)
          _declaredFreshFunctions = _declaredFreshFunctions + f
        }
      }
    }

    def declareAndRecordAsFreshMacros(macros: Seq[MacroDecl]): Unit = {
      for (m <- macros) {
        if (!_declaredFreshMacroNames.contains(m.id.name)) {
          prover.declare(m)
          _declaredFreshMacros = _declaredFreshMacros.appended(m)
          _declaredFreshMacroNames = _declaredFreshMacroNames + m.id.name
        }
      }
    }

    /* Misc */

    def statistics(): Map[String, String] = prover.statistics()

    override def generateModel(): Unit = {
      val timeout = Verifier.config.assertTimeout.toOption
      proverAssert(False, timeout)(prover.assert(False, timeout))
    }

    override def getModel(): Model = prover.getModel()

    override def hasModel(): Boolean = prover.hasModel()

    override def isModelValid(): Boolean = prover.isModelValid()

    override def clearModel(): Unit = prover.clearLastAssert()
  }
}
