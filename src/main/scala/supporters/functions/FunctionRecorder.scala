// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2019 ETH Zurich.

package viper.silicon.supporters.functions

import scala.util.control.TailCalls.{TailRec, done, tailcall}
import viper.silver.ast
import viper.silicon.common.Mergeable
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.rules.{InverseFunctions, PermMapDefinition, SnapshotMapDefinition}
import viper.silicon.{Map, Stack}
import viper.silicon.state.terms._
import viper.silver.ast.AbstractLocalVar

// TODO: FunctionRecorder records Function, Var, etc., which are later on turned into corresponding
//       declarations (e.g. FunctionDecl), see FunctionData's field freshSymbolsAcrossAllPhases.
//       Only macros are already recorded as MacroDecls — this should be the case for Functions,
//       etc. as well.
trait FunctionRecorder extends Mergeable[FunctionRecorder] {
  def arguments: Option[Seq[(Var, Option[ast.AbstractLocalVar])]]
  private[functions] def locToSnaps: Map[(ast.LocationAccess, Seq[ExpContext]), InsertionOrderedSet[(Stack[Term], Term)]]
  def locToSnap: Map[(ast.LocationAccess, Seq[ExpContext]), Term]
  private[functions] def fappToSnaps: Map[(ast.FuncApp, Seq[ExpContext]), InsertionOrderedSet[(Stack[Term], Term)]]
  def fappToSnap: Map[(ast.FuncApp, Seq[ExpContext]), Term]
  def freshFvfsAndDomains: InsertionOrderedSet[SnapshotMapDefinition]
  def freshPermMaps: InsertionOrderedSet[PermMapDefinition]
  def freshFieldInvs: InsertionOrderedSet[InverseFunctions]
  def freshConstrainedVars: InsertionOrderedSet[(Var, Term)]
  def freshConstraints: InsertionOrderedSet[Term]
  def freshSnapshots: InsertionOrderedSet[Function]
  def freshPathSymbols: InsertionOrderedSet[Function]
  def freshMacros: InsertionOrderedSet[MacroDecl]
  def recordSnapshot(loc: ast.LocationAccess, guards: Stack[Term], snap: Term): FunctionRecorder
  def recordSnapshot(fapp: ast.FuncApp, guards: Stack[Term], snap: Term): FunctionRecorder
  def recordFvfAndDomain(fvfDef: SnapshotMapDefinition): FunctionRecorder
  def recordPermMap(pmDef: PermMapDefinition): FunctionRecorder
  def recordFieldInv(inv: InverseFunctions): FunctionRecorder
  def recordConstrainedVar(v: Var, constraint: Term): FunctionRecorder
  def recordConstraint(constraint: Term): FunctionRecorder
  def recordFreshSnapshot(snap: Function): FunctionRecorder
  def recordPathSymbol(symbol: Function): FunctionRecorder
  def recordFreshMacro(decl: MacroDecl): FunctionRecorder
  def depth: Int
  def changeDepthBy(delta: Int): FunctionRecorder
  def enterLet(l: ast.Let): FunctionRecorder
  def leaveLet(l: ast.Let): FunctionRecorder
  def enterQuantifiedExp(q: ast.QuantifiedExp): FunctionRecorder
  def leaveQuantifiedExp(q: ast.QuantifiedExp): FunctionRecorder
}


trait ExpContext
case class LetContext(l: ast.Let) extends ExpContext
case class QuantifierContext(q: ast.QuantifiedExp) extends ExpContext

case class ActualFunctionRecorder(private val _data: Either[FunctionData, (ast.Predicate, Seq[Var])],
                                  private[functions] val locToSnaps: Map[(ast.LocationAccess, Seq[ExpContext]), InsertionOrderedSet[(Stack[Term], Term)]] = Map(),
                                  private[functions] val fappToSnaps: Map[(ast.FuncApp, Seq[ExpContext]), InsertionOrderedSet[(Stack[Term], Term)]] = Map(),
                                  freshFvfsAndDomains: InsertionOrderedSet[SnapshotMapDefinition] = InsertionOrderedSet(),
                                  freshPermMaps: InsertionOrderedSet[PermMapDefinition] = InsertionOrderedSet(),
                                  freshFieldInvs: InsertionOrderedSet[InverseFunctions] = InsertionOrderedSet(),
                                  freshConstrainedVars: InsertionOrderedSet[(Var, Term)] = InsertionOrderedSet(),
                                  freshConstraints: InsertionOrderedSet[Term] = InsertionOrderedSet(),
                                  freshSnapshots: InsertionOrderedSet[Function] = InsertionOrderedSet(),
                                  freshPathSymbols: InsertionOrderedSet[Function] = InsertionOrderedSet(),
                                  freshMacros: InsertionOrderedSet[MacroDecl] = InsertionOrderedSet(),
                                  depth: Int = 0,
                                  private val _context: Seq[ExpContext] = Seq())
    extends FunctionRecorder {

  /* Depth is intended to reflect how often a nested function application or unfolding expression
   * has been entered, where entered means, that a function application's precondition is consumed
   * or that an unfolded preciate's body is produced (and similar for applying a magic wand).
   * On depth 0, mappings from heap-dependent expressions to snapshots need to be recorded, but not
   * on higher levels, since expressions encountered do not (directly) occur in the function for
   * whose axiomatisation we currently record information.
   * On depths 0 and 1, newly declared symbols, e.g. summarising snapshot maps, need to be recorded
   * because both may occur in the snapshots to which heap-dependent expressions are mapped. Symbols
   * declared on higher depths, however cannot occur in these snapshots, and therefore don't need to
   * be recorded.
   *
   * Consider the following members:
   *
   *   function f()
   *     ...
   *   { ... unfolding P() in g() ... }
   *
   *   function g()
   *     ...
   *     requires ... unfolding Q() in h() ...
   *
   *   predicate P()
   *   { ... unfolding Q() in h() ... }
   *
   * When recording data for axiomatising f(), the production of P's body happens on depth 1, as
   * does the consumption of g's precondition, whereas the same happens for Q and g on depth 2.
   * A snapshot introduced by P/g may occur in any snapshot to which heap-dependent expressions
   * from f's body (or contract) are mapped.
   * However, any snapshot introduced by Q/h cannot occur in a snapshot that is used to axiomatise
   * f: for h, that is because these function applications are not part of f; for Q, that is because
   * snapshots introduced by unfolding Q can only occur in heap-dependent expressions from the
   * unfolding's in-clause, which, as argued before, are not part of f.
   */

  override def arguments: Option[Stack[(Var, Option[AbstractLocalVar])]] = _data match {
    case Left(fd) => Some(fd.argumentsDuringFunctionVerification.zip(fd.argumentExpsDuringFunctionVerification))
    case Right((_, args)) => Some(args.map((_, None)))
  }

  private def exprToSnap[E <: ast.Exp]
                        (recordings: Map[(E, Seq[ExpContext]), InsertionOrderedSet[(Stack[Term], Term)]])
                        : Map[(E, Seq[ExpContext]), Term] = {

    recordings.map { case (expr, guardsToSnap) =>
      expr -> toTerm(guardsToSnap)
    }
  }

  private def toTerm(snaps: InsertionOrderedSet[(Stack[Term], Term)]): Term = {
    assert(snaps.nonEmpty)
    if (snaps.size == 1 || snaps.head._1.isEmpty) return snaps.head._2

    // The input is already deduplicated. Within either branch, removing the common
    // first guard is injective, so partitions need no further set construction.
    // Hashing a set entry here would traverse its entire remaining guard stack at
    // every level. Lists give constant-time tails; vectors retain recording order.
    type Recordings = Vector[(List[Term], Term)]

    def build(recordings: Recordings, alt: Option[Term]): TailRec[Term] = {
      assert(recordings.nonEmpty)
      val (guards, snap) = recordings.head
      if (recordings.size == 1) {
        done(alt match {
          case Some(fallback) => Ite(And(guards.toSet), snap, fallback)
          case None => snap
        })
      } else if (guards.isEmpty) {
        done(snap)
      } else {
        val firstBranch = guards.head
        lazy val oppositeBranch = Not(firstBranch)
        val positive = Vector.newBuilder[(List[Term], Term)]
        val negative = Vector.newBuilder[(List[Term], Term)]
        val remaining = Vector.newBuilder[(List[Term], Term)]
        recordings.foreach { case entry @ (path, value) =>
          if (path.nonEmpty && path.head == firstBranch)
            positive += ((path.tail, value))
          else if (path.nonEmpty && path.head == oppositeBranch)
            negative += ((path.tail, value))
          else
            remaining += entry
        }
        val leftRecordings = positive.result()
        val rightRecordings = negative.result()
        val otherRecordings = remaining.result()

        // Evaluate the fallback first, then the two branches, just as in the
        // recursive construction. Trampolining also handles long shared paths
        // whose guards disappear entirely when there is no fallback.
        val fallback: TailRec[Option[Term]] =
          if (otherRecordings.nonEmpty)
            tailcall(build(otherRecordings, alt)).map(t => Some(t))
          else
            done(alt)

        fallback.flatMap { newAlt =>
          // The first recording always belongs to the positive partition.
          if (rightRecordings.nonEmpty) {
            for {
              left <- tailcall(build(leftRecordings, newAlt))
              right <- tailcall(build(rightRecordings, newAlt))
            } yield Ite(firstBranch, left, right)
          } else {
            newAlt match {
              case Some(fallback) =>
                tailcall(build(leftRecordings, newAlt)).map(left => Ite(firstBranch, left, fallback))
              case None => tailcall(build(leftRecordings, None))
            }
          }
        }
      }
    }

    build(snaps.iterator.map { case (guards, snap) => (guards.toList, snap) }.toVector, None).result
  }

  def locToSnap: Map[(ast.LocationAccess, Seq[ExpContext]), Term] = exprToSnap(locToSnaps)
  def fappToSnap: Map[(ast.FuncApp, Seq[ExpContext]), Term] = exprToSnap(fappToSnaps)

  private def recordExpressionSnapshot[E <: ast.Exp]
                                      (loc: E,
                                       guards: Stack[Term],
                                       snap: Term,
                                       recordings: Map[(E, Seq[ExpContext]), InsertionOrderedSet[(Stack[Term], Term)]])
                                      : Option[Map[(E, Seq[ExpContext]), InsertionOrderedSet[(Stack[Term], Term)]]] = {

    if (depth == 0) {
      val guardsToSnaps = recordings.getOrElse((loc, _context), InsertionOrderedSet()) + (guards -> snap)

      Some(recordings + ((loc, _context) -> guardsToSnaps))
    } else {
      None
    }
  }

  def recordSnapshot(loc: ast.LocationAccess, guards: Stack[Term], snap: Term)
                    : ActualFunctionRecorder = {

    recordExpressionSnapshot(loc, guards, snap, locToSnaps)
      .fold(this)(updatedLocToSnaps => copy(locToSnaps = updatedLocToSnaps))
  }

  def recordSnapshot(fapp: ast.FuncApp, guards: Stack[Term], snap: Term)
                    : ActualFunctionRecorder = {

    recordExpressionSnapshot(fapp, guards, snap, fappToSnaps)
      .fold(this)(updatedFAppToSnaps => copy(fappToSnaps = updatedFAppToSnaps))
  }

  def recordFvfAndDomain(fvfDef: SnapshotMapDefinition): ActualFunctionRecorder =
    if (depth <= 2) copy(freshFvfsAndDomains = freshFvfsAndDomains + fvfDef)
    else this

  def recordPermMap(pmDef: PermMapDefinition): ActualFunctionRecorder =
    if (depth <= 2) copy(freshPermMaps = freshPermMaps + pmDef)
    else this

  def recordFieldInv(inv: InverseFunctions): ActualFunctionRecorder =
    if (depth <= 2) copy(freshFieldInvs = freshFieldInvs + inv)
    else this

  def recordConstrainedVar(arp: Var, constraint: Term): ActualFunctionRecorder =
    if (depth <= 2) copy(freshConstrainedVars = freshConstrainedVars + ((arp, constraint)))
    else this

  def recordConstraint(constraint: Term): ActualFunctionRecorder =
    if (depth <= 2) copy(freshConstraints = freshConstraints + constraint)
    else this

  def recordFreshSnapshot(snap: Function): ActualFunctionRecorder =
    if (depth <= 3) copy(freshSnapshots = freshSnapshots + snap)
    else this

  def recordPathSymbol(symbol: Function): ActualFunctionRecorder =
    if (depth <= 2) copy(freshPathSymbols = freshPathSymbols + symbol)
    else this

  def recordFreshMacro(decl: MacroDecl): FunctionRecorder =
    if (depth <= 1) copy(freshMacros = freshMacros + decl)
    else this

  def changeDepthBy(delta: Int): ActualFunctionRecorder =
    copy(depth = depth + delta)

  def enterLet(l: ast.Let): FunctionRecorder = {
    copy(_context = _context :+ LetContext(l))
  }

  def leaveLet(l: ast.Let): FunctionRecorder = {
    assert(_context.nonEmpty && (_context.last match {
      case LetContext(`l`) => true
      case _ => false
    }))
    copy(_context = _context.init)
  }

  def enterQuantifiedExp(q: ast.QuantifiedExp): FunctionRecorder = {
    copy(_context = _context :+ QuantifierContext(q))
  }

  def leaveQuantifiedExp(q: ast.QuantifiedExp): FunctionRecorder = {
    assert(_context.nonEmpty && (_context.last match {
      case QuantifierContext(`q`) => true
      case _ => false
    }))
    copy(_context = _context.init)
  }

  def merge(other: FunctionRecorder): ActualFunctionRecorder = {
    if (depth > 1) return this

    assert(other.getClass == this.getClass)

    var lts = locToSnaps
    var fts = fappToSnaps

    if (depth == 0) {
      lts =
        other.locToSnaps.foldLeft(locToSnaps){case (accLts, (loc, guardsToSnaps)) =>
          val guardsToSnaps1 = accLts.getOrElse(loc, InsertionOrderedSet()) ++ guardsToSnaps
          accLts + (loc -> guardsToSnaps1)
        }

      fts =
        other.fappToSnaps.foldLeft(fappToSnaps){case (accFts, (fapp, guardsToSnaps)) =>
          val guardsToSnaps1 = accFts.getOrElse(fapp, InsertionOrderedSet()) ++ guardsToSnaps
          accFts + (fapp -> guardsToSnaps1)
        }
    }

    val fvfs = freshFvfsAndDomains ++ other.freshFvfsAndDomains
    val pmDefs = freshPermMaps ++ other.freshPermMaps
    val fieldInvs = freshFieldInvs ++ other.freshFieldInvs
    val arps = freshConstrainedVars ++ other.freshConstrainedVars
    val constraints = freshConstraints ++ other.freshConstraints
    val snaps = freshSnapshots ++ other.freshSnapshots
    val symbols = freshPathSymbols ++ other.freshPathSymbols
    val macros = freshMacros ++ other.freshMacros

    copy(locToSnaps = lts,
         fappToSnaps = fts,
         freshFvfsAndDomains = fvfs,
         freshPermMaps = pmDefs,
         freshFieldInvs = fieldInvs,
         freshConstrainedVars = arps,
         freshConstraints = constraints,
         freshSnapshots = snaps,
         freshPathSymbols = symbols,
         freshMacros = macros)
  }

  override lazy val toString: String = {
    val ltsStrs = locToSnaps map {case (k, v) => s"$k  |==>  $v"}
    val ftsStrs = fappToSnap map {case (k, v) => s"$k  |==>  $v"}

    s"""SnapshotRecorder(
        |  locToSnaps:
        |    ${ltsStrs.mkString("\n    ")}
        |  fappToSnap:
        |    ${ftsStrs.mkString("\n    ")}
        |  ...
        |)
     """.stripMargin
  }
}

case object NoopFunctionRecorder extends FunctionRecorder {
  def arguments: Option[Seq[(viper.silicon.state.terms.Var, Option[viper.silver.ast.AbstractLocalVar])]] = None
  private[functions] val fappToSnaps: Map[(ast.FuncApp, Seq[ExpContext]), InsertionOrderedSet[(Stack[Term], Term)]] = Map.empty
  val fappToSnap: Map[(ast.FuncApp, Seq[ExpContext]), Term] = Map.empty
  private[functions] val locToSnaps: Map[(ast.LocationAccess, Seq[ExpContext]), InsertionOrderedSet[(Stack[Term], Term)]] = Map.empty
  val locToSnap: Map[(ast.LocationAccess, Seq[ExpContext]), Term] = Map.empty

  val freshFvfsAndDomains: InsertionOrderedSet[SnapshotMapDefinition] = InsertionOrderedSet.empty
  val freshPermMaps: InsertionOrderedSet[PermMapDefinition] = InsertionOrderedSet.empty
  val freshFieldInvs: InsertionOrderedSet[InverseFunctions] = InsertionOrderedSet.empty
  val freshConstrainedVars: InsertionOrderedSet[(Var, Term)] = InsertionOrderedSet.empty
  val freshConstraints: InsertionOrderedSet[Term] = InsertionOrderedSet.empty
  val freshSnapshots: InsertionOrderedSet[Function] = InsertionOrderedSet.empty
  val freshPathSymbols: InsertionOrderedSet[Function] = InsertionOrderedSet.empty
  val freshMacros: InsertionOrderedSet[MacroDecl] = InsertionOrderedSet.empty
  val depth = 0

  def merge(other: FunctionRecorder): NoopFunctionRecorder.type = {
    assert(other == this)

    this
  }

  def recordSnapshot(loc: ast.LocationAccess, guards: Stack[Term], snap: Term): NoopFunctionRecorder.type = this
  def recordFvfAndDomain(fvfDef: SnapshotMapDefinition): NoopFunctionRecorder.type = this
  def recordPermMap(pmDef: PermMapDefinition): NoopFunctionRecorder.type = this
  def recordFieldInv(inv: InverseFunctions): NoopFunctionRecorder.type = this
  def recordSnapshot(fapp: ast.FuncApp, guards: Stack[Term], snap: Term): NoopFunctionRecorder.type = this
  def recordConstrainedVar(arp: Var, constraint: Term): NoopFunctionRecorder.type = this
  def recordConstraint(constraint: Term): NoopFunctionRecorder.type = this
  def recordFreshSnapshot(snap: Function): NoopFunctionRecorder.type = this
  def recordPathSymbol(symbol: Function): NoopFunctionRecorder.type = this
  def recordFreshMacro(decl: MacroDecl): NoopFunctionRecorder.type = this
  def changeDepthBy(delta: Int): NoopFunctionRecorder.type = this
  def enterLet(l: ast.Let): FunctionRecorder = this
  def leaveLet(l: ast.Let): FunctionRecorder = this
  def enterQuantifiedExp(q: ast.QuantifiedExp): FunctionRecorder = this
  def leaveQuantifiedExp(q: ast.QuantifiedExp): FunctionRecorder = this
}
