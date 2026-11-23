// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.supporters

import java.util.IdentityHashMap
import scala.collection.mutable
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.state.MagicWandIdentifier
import viper.silver.ast
import viper.silver.ast.utility.Nodes
import viper.silver.ast.utility.QuantifiedPermissions.QuantifiedPermissionAssertion

/** Single-pass structural pre-analysis of a translated [[ast.Program]].
  *
  * Replaces repeated whole-program and transitive-dependency AST traversals across preamble contributors,
  * function analysis, and per-member initial-state creation while preserving exact collection contents and
  * insertion orders.
  */
final class ProgramAnalysis private (val program: ast.Program) {
  import ProgramAnalysis.NodeSummary

  private val fullSummaries = new IdentityHashMap[ast.Member, NodeSummary]()
  private val specSummaries = new IdentityHashMap[ast.Member, NodeSummary]()

  private val _fvfFields = mutable.LinkedHashSet.empty[ast.Field]
  private val _psfPredicates = mutable.LinkedHashSet.empty[ast.Predicate]
  private val _quantifiedWands = mutable.LinkedHashSet.empty[MagicWandIdentifier]
  private val _accessedPredicates = mutable.LinkedHashSet.empty[String]
  private val _backendTypes = mutable.LinkedHashSet.empty[ast.BackendType]
  private var _hasMagicWand = false

  private val qpFieldsCache = new IdentityHashMap[ast.Member, InsertionOrderedSet[ast.Field]]()
  private val qpPredicatesCache = new IdentityHashMap[ast.Member, InsertionOrderedSet[ast.Predicate]]()
  private val qpWandsCache = new IdentityHashMap[ast.Member, InsertionOrderedSet[MagicWandIdentifier]]()
  private val resTriggersCache = new IdentityHashMap[ast.Member, InsertionOrderedSet[Any]]()

  analyzeProgram()

  val fvfFields: InsertionOrderedSet[ast.Field] = InsertionOrderedSet(_fvfFields)
  val psfPredicates: InsertionOrderedSet[ast.Predicate] = InsertionOrderedSet(_psfPredicates)
  val quantifiedWands: InsertionOrderedSet[MagicWandIdentifier] = InsertionOrderedSet(_quantifiedWands)
  val accessedPredicates: InsertionOrderedSet[String] = InsertionOrderedSet(_accessedPredicates)
  val backendTypes: InsertionOrderedSet[ast.BackendType] = InsertionOrderedSet(_backendTypes)
  val hasMagicWand: Boolean = _hasMagicWand

  def quantifiedFields(member: ast.Member): InsertionOrderedSet[ast.Field] = {
    val cached = qpFieldsCache.get(member)
    if (cached != null) return cached
    val collected = mutable.LinkedHashSet.empty[ast.Field]
    traverseDependencies(member) { summary =>
      collected ++= summary.qFields
    }
    val res = InsertionOrderedSet(collected)
    qpFieldsCache.put(member, res)
    res
  }

  def quantifiedPredicates(member: ast.Member): InsertionOrderedSet[ast.Predicate] = {
    val cached = qpPredicatesCache.get(member)
    if (cached != null) return cached
    val collected = mutable.LinkedHashSet.empty[ast.Predicate]
    traverseDependencies(member) { summary =>
      collected ++= summary.qPredicates
    }
    val res = InsertionOrderedSet(collected)
    qpPredicatesCache.put(member, res)
    res
  }

  def quantifiedMagicWands(member: ast.Member): InsertionOrderedSet[MagicWandIdentifier] = {
    val cached = qpWandsCache.get(member)
    if (cached != null) return cached
    val summary = fullSummaryOf(member)
    val res = InsertionOrderedSet(summary.qWands)
    qpWandsCache.put(member, res)
    res
  }

  def resourceTriggers(member: ast.Member): InsertionOrderedSet[Any] = {
    val cached = resTriggersCache.get(member)
    if (cached != null) return cached
    val collected = mutable.LinkedHashSet.empty[Any]
    traverseDependencies(member) { summary =>
      collected ++= summary.resTriggers
    }
    val res = InsertionOrderedSet(collected)
    resTriggersCache.put(member, res)
    res
  }

  private def fullSummaryOf(member: ast.Member): NodeSummary = {
    var s = fullSummaries.get(member)
    if (s == null) {
      s = new NodeSummary()
      scanNode(member, s, recordProgramGlobals = false)
      fullSummaries.put(member, s)
    }
    s
  }

  private def specSummaryOf(member: ast.Member): NodeSummary = {
    var s = specSummaries.get(member)
    if (s == null) {
      s = member match {
        case m: ast.Method =>
          val ns = new NodeSummary()
          m.pres.foreach(scanNode(_, ns, recordProgramGlobals = false))
          m.posts.foreach(scanNode(_, ns, recordProgramGlobals = false))
          ns
        case f: ast.Function =>
          val ns = new NodeSummary()
          f.pres.foreach(scanNode(_, ns, recordProgramGlobals = false))
          f.posts.foreach(scanNode(_, ns, recordProgramGlobals = false))
          ns
        case other =>
          fullSummaryOf(other)
      }
      specSummaries.put(member, s)
    }
    s
  }

  /** Matches [[QuantifiedPermissions.collectInDependencies]] breadth-first visit order. */
  private def traverseDependencies(root: ast.Member)(visitSummary: NodeSummary => Unit): Unit = {
    val visited = new IdentityHashMap[ast.Member, java.lang.Boolean]()
    val queue = new java.util.ArrayDeque[ast.Member]()

    val rootSummary = fullSummaryOf(root)
    queue.add(root)
    rootSummary.referencedMembers.foreach(queue.add)

    while (!queue.isEmpty) {
      val cur = queue.poll()
      val summary = if (cur eq root) rootSummary else specSummaryOf(cur)
      visited.put(cur, java.lang.Boolean.TRUE)
      visitSummary(summary)
      summary.referencedMembers.foreach { m =>
        if (!visited.containsKey(m)) queue.add(m)
      }
    }
  }

  private def analyzeProgram(): Unit = {
    program.domains.foreach { d =>
      val s = new NodeSummary()
      scanNode(d, s, recordProgramGlobals = true)
      fullSummaries.put(d, s)
    }
    program.fields.foreach { f =>
      val s = new NodeSummary()
      scanType(f.typ)
      fullSummaries.put(f, s)
    }
    program.functions.foreach { f =>
      val spec = new NodeSummary()
      val full = new NodeSummary()
      f.formalArgs.foreach(a => scanType(a.typ))
      scanType(f.typ)
      f.pres.foreach { e =>
        scanNode(e, spec, recordProgramGlobals = true)
      }
      f.posts.foreach { e =>
        scanNode(e, spec, recordProgramGlobals = true)
      }
      full.mergeFrom(spec)
      f.body.foreach(scanNode(_, full, recordProgramGlobals = true))
      specSummaries.put(f, spec)
      fullSummaries.put(f, full)
    }
    program.predicates.foreach { p =>
      val s = new NodeSummary()
      p.formalArgs.foreach(a => scanType(a.typ))
      p.body.foreach(scanNode(_, s, recordProgramGlobals = true))
      fullSummaries.put(p, s)
      specSummaries.put(p, s)
    }
    program.methods.foreach { m =>
      val spec = new NodeSummary()
      val full = new NodeSummary()
      m.formalArgs.foreach(a => scanType(a.typ))
      m.formalReturns.foreach(r => scanType(r.typ))
      m.pres.foreach(scanNode(_, spec, recordProgramGlobals = true))
      m.posts.foreach(scanNode(_, spec, recordProgramGlobals = true))
      full.mergeFrom(spec)
      m.body.foreach(scanNode(_, full, recordProgramGlobals = true))
      specSummaries.put(m, spec)
      fullSummaries.put(m, full)
    }
    program.extensions.foreach { ext =>
      val s = new NodeSummary()
      scanNode(ext, s, recordProgramGlobals = true)
      fullSummaries.put(ext, s)
    }
  }

  private def scanType(t: ast.Type): Unit = t match {
    case bt: ast.BackendType => _backendTypes += bt
    case gt: ast.GenericType => gt.typeArguments.foreach(scanType)
    case _ =>
  }

  private def scanTriggerDeep(n: ast.Node): Unit = n match {
    case fa: ast.FieldAccess =>
      scanTriggerDeep(fa.rcv)
      _fvfFields += fa.field
    case pa: ast.PredicateAccess =>
      pa.args.foreach(scanTriggerDeep)
      _psfPredicates += program.findPredicate(pa.predicateName)
    case b: ast.BinExp =>
      scanTriggerDeep(b.left)
      scanTriggerDeep(b.right)
    case u: ast.UnExp =>
      scanTriggerDeep(u.exp)
    case fa: ast.FuncApp =>
      fa.args.foreach(scanTriggerDeep)
    case dfa: ast.DomainFuncApp =>
      dfa.args.foreach(scanTriggerDeep)
    case _: ast.Literal | _: ast.AbstractLocalVar =>
    case other =>
      Nodes.subnodes(other).foreach(scanTriggerDeep)
  }

  private def handleQuantifierTriggers(triggers: Seq[ast.Trigger], s: NodeSummary, recordProgramGlobals: Boolean): Unit = {
    triggers.foreach { trig =>
      trig.exps.foreach { exp =>
        exp match {
          case fa: ast.FieldAccess =>
            s.qFields += fa.field
            s.resTriggers += fa.field
          case pa: ast.PredicateAccess =>
            val pred = program.findPredicate(pa.predicateName)
            s.qPredicates += pred
            s.resTriggers += pred
          case mw: ast.MagicWand =>
            val wid = MagicWandIdentifier(mw, program)
            s.qWands += wid
            s.resTriggers += wid
            if (recordProgramGlobals) _quantifiedWands += wid
          case ra: ast.ResourceAccess =>
            s.resTriggers += ra.res(program)
          case _ =>
        }
        if (recordProgramGlobals) scanTriggerDeep(exp)
      }
    }
  }

  private def scanNode(n: ast.Node, s: NodeSummary, recordProgramGlobals: Boolean): Unit = {
    n match {
      case t: ast.Type =>
        if (recordProgramGlobals) scanType(t)
        return
      case d: ast.LocalVarDecl =>
        if (recordProgramGlobals) scanType(d.typ)
        return
      case _: ast.Literal | _: ast.AbstractLocalVar | _: ast.WildcardPerm | _: ast.FullPerm | _: ast.NoPerm | _: ast.EpsilonPerm | _: ast.Goto =>
        return
      case mc: ast.MethodCall =>
        s.addRef(program.findMethod(mc.methodName))
        mc.args.foreach(scanNode(_, s, recordProgramGlobals))
        mc.targets.foreach(scanNode(_, s, recordProgramGlobals))
        return
      case fa: ast.FuncApp =>
        s.addRef(program.findFunction(fa.funcname))
        fa.args.foreach(scanNode(_, s, recordProgramGlobals))
        return
      case uf: ast.Unfolding =>
        s.addRef(program.findPredicate(uf.acc.loc.predicateName))
        scanNode(uf.acc, s, recordProgramGlobals)
        scanNode(uf.body, s, recordProgramGlobals)
        return
      case uf: ast.Unfold =>
        s.addRef(program.findPredicate(uf.acc.loc.predicateName))
        scanNode(uf.acc, s, recordProgramGlobals)
        return
      case fl: ast.Fold =>
        s.addRef(program.findPredicate(fl.acc.loc.predicateName))
        scanNode(fl.acc, s, recordProgramGlobals)
        return
      case pa: ast.PredicateAccess =>
        if (recordProgramGlobals) _accessedPredicates += pa.predicateName
        pa.args.foreach(scanNode(_, s, recordProgramGlobals))
        return
      case mw: ast.MagicWand =>
        if (recordProgramGlobals) _hasMagicWand = true
        scanNode(mw.left, s, recordProgramGlobals)
        scanNode(mw.right, s, recordProgramGlobals)
        return
      case q: ast.Forall =>
        q match {
          case QuantifiedPermissionAssertion(_, _, acc: ast.FieldAccessPredicate) =>
            s.qFields += acc.loc.field
            if (recordProgramGlobals) _fvfFields += acc.loc.field
          case QuantifiedPermissionAssertion(_, _, acc: ast.PredicateAccessPredicate) =>
            val pred = program.findPredicate(acc.loc.predicateName)
            s.qPredicates += pred
            if (recordProgramGlobals) _psfPredicates += pred
          case QuantifiedPermissionAssertion(_, _, wand: ast.MagicWand) =>
            val wid = MagicWandIdentifier(wand, program)
            s.qWands += wid
            if (recordProgramGlobals) _quantifiedWands += wid
          case _ =>
        }
        handleQuantifierTriggers(q.triggers, s, recordProgramGlobals)
        q.variables.foreach(v => if (recordProgramGlobals) scanType(v.typ))
        q.triggers.foreach(t => t.exps.foreach(scanNode(_, s, recordProgramGlobals)))
        scanNode(q.exp, s, recordProgramGlobals)
        return
      case q: ast.Exists =>
        handleQuantifierTriggers(q.triggers, s, recordProgramGlobals)
        q.variables.foreach(v => if (recordProgramGlobals) scanType(v.typ))
        q.triggers.foreach(t => t.exps.foreach(scanNode(_, s, recordProgramGlobals)))
        scanNode(q.exp, s, recordProgramGlobals)
        return
      case seqn: ast.Seqn =>
        seqn.ss.foreach(scanNode(_, s, recordProgramGlobals))
        if (recordProgramGlobals) {
          seqn.scopedDecls.foreach {
            case l: ast.LocalVarDecl => scanType(l.typ)
            case _ =>
          }
        }
        return
      case iff: ast.If =>
        scanNode(iff.cond, s, recordProgramGlobals)
        scanNode(iff.thn, s, recordProgramGlobals)
        scanNode(iff.els, s, recordProgramGlobals)
        return
      case whl: ast.While =>
        scanNode(whl.cond, s, recordProgramGlobals)
        whl.invs.foreach(scanNode(_, s, recordProgramGlobals))
        scanNode(whl.body, s, recordProgramGlobals)
        return
      case lva: ast.LocalVarAssign =>
        scanNode(lva.lhs, s, recordProgramGlobals)
        scanNode(lva.rhs, s, recordProgramGlobals)
        return
      case fa: ast.FieldAssign =>
        scanNode(fa.lhs, s, recordProgramGlobals)
        scanNode(fa.rhs, s, recordProgramGlobals)
        return
      case inh: ast.Inhale =>
        scanNode(inh.exp, s, recordProgramGlobals)
        return
      case exh: ast.Exhale =>
        scanNode(exh.exp, s, recordProgramGlobals)
        return
      case asrt: ast.Assert =>
        scanNode(asrt.exp, s, recordProgramGlobals)
        return
      case assm: ast.Assume =>
        scanNode(assm.exp, s, recordProgramGlobals)
        return
      case fldAcc: ast.FieldAccess =>
        scanNode(fldAcc.rcv, s, recordProgramGlobals)
        return
      case fap: ast.FieldAccessPredicate =>
        scanNode(fap.loc, s, recordProgramGlobals)
        fap.perm.foreach(scanNode(_, s, recordProgramGlobals))
        return
      case pap: ast.PredicateAccessPredicate =>
        scanNode(pap.loc, s, recordProgramGlobals)
        pap.perm.foreach(scanNode(_, s, recordProgramGlobals))
        return
      case ce: ast.CondExp =>
        scanNode(ce.cond, s, recordProgramGlobals)
        scanNode(ce.thn, s, recordProgramGlobals)
        scanNode(ce.els, s, recordProgramGlobals)
        return
      case lt: ast.Let =>
        if (recordProgramGlobals) scanType(lt.variable.typ)
        scanNode(lt.exp, s, recordProgramGlobals)
        scanNode(lt.body, s, recordProgramGlobals)
        return
      case ie: ast.InhaleExhaleExp =>
        scanNode(ie.in, s, recordProgramGlobals)
        scanNode(ie.ex, s, recordProgramGlobals)
        return
      case b: ast.BinExp =>
        scanNode(b.left, s, recordProgramGlobals)
        scanNode(b.right, s, recordProgramGlobals)
        return
      case u: ast.UnExp =>
        scanNode(u.exp, s, recordProgramGlobals)
        return
      case dfa: ast.DomainFuncApp =>
        dfa.args.foreach(scanNode(_, s, recordProgramGlobals))
        if (recordProgramGlobals) {
          dfa.typVarMap.keys.foreach(scanType)
          dfa.typVarMap.values.foreach(scanType)
        }
        return
      case _ =>
    }
    Nodes.subnodes(n).foreach(scanNode(_, s, recordProgramGlobals))
  }
}

object ProgramAnalysis {
  @volatile private var lastCached: ProgramAnalysis = _

  def of(program: ast.Program): ProgramAnalysis = {
    val cur = lastCached
    if (cur != null && (cur.program eq program)) cur
    else {
      val fresh = new ProgramAnalysis(program)
      lastCached = fresh
      fresh
    }
  }

  private final class NodeSummary {
    val qFields = mutable.LinkedHashSet.empty[ast.Field]
    val qPredicates = mutable.LinkedHashSet.empty[ast.Predicate]
    val qWands = mutable.LinkedHashSet.empty[MagicWandIdentifier]
    val resTriggers = mutable.LinkedHashSet.empty[Any]
    val referencedMembers = mutable.ArrayBuffer.empty[ast.Member]
    private val referencedSeen = new IdentityHashMap[ast.Member, java.lang.Boolean]()

    def addRef(m: ast.Member): Unit = {
      if (referencedSeen.put(m, java.lang.Boolean.TRUE) == null) {
        referencedMembers += m
      }
    }

    def mergeFrom(other: NodeSummary): Unit = {
      qFields ++= other.qFields
      qPredicates ++= other.qPredicates
      qWands ++= other.qWands
      resTriggers ++= other.resTriggers
      other.referencedMembers.foreach(addRef)
    }
  }

  /** Zero-allocation check for whether `node` contains any `Forall` or `Exists` node. */
  def containsQuantifier(node: ast.Node): Boolean = node match {
    case _: ast.Forall | _: ast.Exists => true
    case _: ast.Literal | _: ast.AbstractLocalVar | _: ast.LocalVarDecl | _: ast.Field |
         _: ast.WildcardPerm | _: ast.FullPerm | _: ast.NoPerm | _: ast.EpsilonPerm |
         _: ast.Goto | _: ast.Type => false
    case m: ast.Method =>
      m.pres.exists(containsQuantifier) || m.posts.exists(containsQuantifier) || m.body.exists(containsQuantifier)
    case f: ast.Function =>
      f.pres.exists(containsQuantifier) || f.posts.exists(containsQuantifier) || f.body.exists(containsQuantifier)
    case p: ast.Predicate =>
      p.body.exists(containsQuantifier)
    case d: ast.Domain =>
      d.axioms.exists(a => containsQuantifier(a.exp))
    case seqn: ast.Seqn =>
      seqn.ss.exists(containsQuantifier)
    case iff: ast.If =>
      containsQuantifier(iff.cond) || containsQuantifier(iff.thn) || containsQuantifier(iff.els)
    case whl: ast.While =>
      containsQuantifier(whl.cond) || whl.invs.exists(containsQuantifier) || containsQuantifier(whl.body)
    case lva: ast.LocalVarAssign =>
      containsQuantifier(lva.lhs) || containsQuantifier(lva.rhs)
    case fa: ast.FieldAssign =>
      containsQuantifier(fa.lhs) || containsQuantifier(fa.rhs)
    case inh: ast.Inhale => containsQuantifier(inh.exp)
    case exh: ast.Exhale => containsQuantifier(exh.exp)
    case asrt: ast.Assert => containsQuantifier(asrt.exp)
    case assm: ast.Assume => containsQuantifier(assm.exp)
    case mc: ast.MethodCall => mc.args.exists(containsQuantifier) || mc.targets.exists(containsQuantifier)
    case fld: ast.Fold => containsQuantifier(fld.acc)
    case ufld: ast.Unfold => containsQuantifier(ufld.acc)
    case uf: ast.Unfolding => containsQuantifier(uf.acc) || containsQuantifier(uf.body)
    case fap: ast.FieldAccessPredicate => containsQuantifier(fap.loc) || fap.perm.exists(containsQuantifier)
    case pap: ast.PredicateAccessPredicate => containsQuantifier(pap.loc) || pap.perm.exists(containsQuantifier)
    case fa: ast.FieldAccess => containsQuantifier(fa.rcv)
    case pa: ast.PredicateAccess => pa.args.exists(containsQuantifier)
    case fapp: ast.FuncApp => fapp.args.exists(containsQuantifier)
    case dfa: ast.DomainFuncApp => dfa.args.exists(containsQuantifier)
    case ce: ast.CondExp => containsQuantifier(ce.cond) || containsQuantifier(ce.thn) || containsQuantifier(ce.els)
    case lt: ast.Let => containsQuantifier(lt.exp) || containsQuantifier(lt.body)
    case ie: ast.InhaleExhaleExp => containsQuantifier(ie.in) || containsQuantifier(ie.ex)
    case b: ast.BinExp => containsQuantifier(b.left) || containsQuantifier(b.right)
    case u: ast.UnExp => containsQuantifier(u.exp)
    case other => Nodes.subnodes(other).exists(containsQuantifier)
  }
}
