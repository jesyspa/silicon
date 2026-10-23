// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2019 ETH Zurich.

package viper.silicon.decider

import scala.collection.mutable
import viper.silver.ast
import viper.silver.components.StatefulComponent
import viper.silicon.interfaces.decider.TermConverter
import viper.silicon.state.{Identifier, MagicWandIdentifier, SimpleIdentifier, SortBasedIdentifier, SuffixedIdentifier}
import viper.silicon.state.terms._

/** Renders terms, sorts and declarations as SMT-LIB 2 text.
  *
  * Output is written directly into a `StringBuilder`, on a single line (except for the warning comment emitted for
  * quantifiers without variables). When a term contains many repeated subterms, `convert(t: Term)` binds the
  * repeated subterms with `let`s, so that each is rendered only once; see [[SharingAnalysis]].
  */
class TermToSMTLib2Converter
    extends TermConverter[String, String, String]
       with StatefulComponent {

  private var sanitizedNamesCache: mutable.Map[String, String] = _

  private val nameSanitizer = new SmtlibNameSanitizer

  /* Subterms bound by `let`s in the term currently being converted, or null if there are none in scope.
   * Like `sanitizedNamesCache`, this state requires that an instance is used by one thread at a time; each verifier
   * owns its converter.
   * Only nodes whose binding level is below `bindingLimit` may be referenced by name: while rendering the
   * definition of a node at level L, only names from levels < L are bound.
   */
  private var sharedTerms: java.util.HashMap[Term, SharingInfo] = _
  private var bindingLimit: Int = Int.MaxValue

  def convert(s: Sort): String = {
    val sb = new StringBuilder
    renderSort(s, false, sb)
    sb.toString
  }

  def convertSanitized(s: Sort): String = {
    val sb = new StringBuilder
    renderSort(s, true, sb)
    sb.toString
  }

  protected def renderSort(sort: Sort, alwaysSanitize: Boolean, sb: StringBuilder): Unit = sort match {
    case sorts.Int => sb.append("Int")
    case sorts.Bool => sb.append("Bool")
    case sorts.Perm => sb.append("$Perm")
    case sorts.Snap => sb.append("$Snap")
    case sorts.Ref => sb.append("$Ref")
    case sorts.Map(keySort, valueSort) =>
      sb.append("Map<"); renderSort(keySort, true, sb); sb.append("~_"); renderSort(valueSort, true, sb); sb.append('>')
    case sorts.Seq(elementSort) => sb.append("Seq<"); renderSort(elementSort, true, sb); sb.append('>')
    case sorts.Set(elementSort) => sb.append("Set<"); renderSort(elementSort, true, sb); sb.append('>')
    case sorts.Multiset(elementSort) => sb.append("Multiset<"); renderSort(elementSort, true, sb); sb.append('>')
    case sorts.HeapSort(valueSort) => sb.append("$Hp<"); renderSort(valueSort, true, sb); sb.append('>')
    case sorts.PredHeapSort => sb.append("$Hp<$Pred>")
    case sorts.PredMaskSort => sb.append("$Hp<$PredMask>")
    case sorts.WandHeapSort => sb.append("$Hp<$MWSF>")
    case sorts.UserSort(id) => sb.append(render(id))
    case sorts.SMTSort(id) => sb.append(if (alwaysSanitize) render(id) else id.name)

    case sorts.Unit =>
      /* Sort Unit corresponds to Scala's Unit type and is used, e.g., as the
       * domain sort of nullary functions.
       */
      ()

    case sorts.FieldValueFunction(_, fieldName) => sb.append("$FVF<").append(fieldName).append('>')
    case sorts.PredicateSnapFunction(_, predName) => sb.append("$PSF<").append(predName).append('>')

    case sorts.FieldPermFunction() => sb.append("$FPM")
    case sorts.PredicatePermFunction() => sb.append("$PPM")

    case sorts.MagicWandSnapFunction => sb.append("$MWSF")
  }

  def convert(d: Decl): String = {
    val sb = new StringBuilder
    withoutSharing(renderDecl(d, sb))
    sb.toString
  }

  protected def renderDecl(decl: Decl, sb: StringBuilder): Unit = decl match {
    case SortDecl(sort: Sort) =>
      sb.append("(declare-sort "); renderSort(sort, false, sb); sb.append(" 0)")

    case FunctionDecl(fun: Function) =>
      if (fun.argSorts.isEmpty) {
        sb.append("(declare-const ").append(render(fun.id)).append(' ')
      } else {
        sb.append("(declare-fun ").append(render(fun.id)).append(" (")
        renderSeparated(fun.argSorts, sb)((s, sb) => renderSort(s, false, sb))
        sb.append(") ")
      }
      renderSort(fun.resultSort, false, sb)
      sb.append(')')

    case swd @ SortWrapperDecl(from, to) =>
      renderDecl(FunctionDecl(Fun(swd.id, from, to)), sb)

    case MacroDecl(id, args, body) =>
      sb.append("(define-fun ").append(render(id)).append(" (")
      renderSeparated(args, sb)(renderVarDecl)
      sb.append(") ")
      renderSort(body.sort, false, sb)
      sb.append(' ')
      renderTerm(body, sb)
      sb.append(')')
  }

  def convert(t: Term): String = {
    val sb = new StringBuilder
    SharingAnalysis.analyse(t) match {
      case None =>
        /* Leaves the fields untouched, so that converters that never share (e.g. the global ones used for reporting)
         * are not affected by races on them. */
        renderTerm(t, sb)
      case Some((shared, levels)) =>
        val outerShared = sharedTerms
        val outerLimit = bindingLimit
        try {
          sharedTerms = shared
          renderLets(t, levels, sb)
        } finally {
          sharedTerms = outerShared
          bindingLimit = outerLimit
        }
    }
    sb.toString
  }

  /* Emits `(let ((n e) ...) (let (...) ... body))`, one `let` per binding level. */
  private def renderLets(body: Term, levels: Array[mutable.ArrayBuffer[Term]], sb: StringBuilder): Unit = {
    var level = 0
    while (level < levels.length) {
      bindingLimit = level
      sb.append("(let (")
      var first = true
      levels(level) foreach (t => {
        if (!first) sb.append(' ')
        first = false
        sb.append('(').append(sharedTerms.get(t).name).append(' ')
        renderNode(t, sb)
        sb.append(')')
      })
      sb.append(") ")
      level += 1
    }
    bindingLimit = Int.MaxValue
    renderTerm(body, sb)
    level = 0
    while (level < levels.length) { sb.append(')'); level += 1 }
  }

  /* Bodies of binders (quantifiers, lets, macros) must not refer to outer let-bound names: the binder might bind a
   * variable that occurs in a shared term, which would capture it. */
  @inline
  private def withoutSharing(render: => Unit): Unit = {
    val outerShared = sharedTerms
    if (outerShared == null) {
      render
    } else {
      sharedTerms = null
      try render
      finally sharedTerms = outerShared
    }
  }

  private def renderHeapType(s: Sort, sb: StringBuilder): Unit = s match {
    case sorts.HeapSort(valueSort) => renderSort(valueSort, true, sb)
    case sorts.PredHeapSort => sb.append("$Pred")
    case sorts.PredMaskSort => sb.append("$PredMask")
    case sorts.WandHeapSort => sb.append("$MWSF")
  }

  protected def renderTerm(term: Term, sb: StringBuilder): Unit = {
    if (sharedTerms != null) {
      val info = sharedTerms.get(term)
      if (info != null && info.name != null && info.level < bindingLimit) {
        sb.append(info.name)
        return
      }
    }

    renderNode(term, sb)
  }

  /* Renders the outermost node of `term`; subterms are rendered via `renderTerm`. */
  private def renderNode(term: Term, sb: StringBuilder): Unit = term match {
    case hvr: HasVarRepr if hvr.varRepr.isDefined => renderTerm(hvr.varRepr.get, sb)

    case lit: Literal => renderLiteral(lit, sb)

    case Ite(t0, t1, t2) => renderOp("ite", t0, t1, t2, sb)

    case x: Var => sb.append(render(x.id))

    case fapp: Application[_] =>
      fapp.applicable match {
        case _: SMTFun => renderSMTApp(fapp.applicable.id.name, fapp.args, sb)
        case _ => renderApp(fapp.applicable.id.name, fapp.args, fapp.sort, sb)
      }

    case q: Quantification => withoutSharing(renderQuantification(q, sb))

    /* Booleans */

    case Not(t) => renderOp("not", t, sb)
    case PermNegation(t) => renderOp("-", t, sb)
    case And(ts) => renderOp("and", ts, sb)
    case Or(ts) => renderOp("or", ts, sb)
    case Implies(t0, t1) => renderOp("=>", t0, t1, sb)
    case bop: Iff => renderTerm(And(Seq(Implies(bop.p0, bop.p1), Implies(bop.p1, bop.p0))), sb)
    case BuiltinEquals(t0, t1) => renderOp("=", t0, t1, sb)

    case bop: CustomEquals => bop.p0.sort match {
      case _: sorts.Seq => renderOp("Seq_equal", bop.p0, bop.p1, sb)
      case _: sorts.Set => renderOp(sanitize("Set_equal"), bop.p0, bop.p1, sb)
      case _: sorts.Multiset => renderOp(sanitize("Multiset_equal"), bop.p0, bop.p1, sb)
      case _: sorts.Map => renderOp(sanitize("Map_equal"), bop.p0, bop.p1, sb)
      case sort => sys.error(s"Don't know how to translate equality between symbols $sort-typed terms")
    }

    /* Arithmetic */

    case Minus(t0, t1) => renderOp("-", t0, t1, sb)
    case Plus(t0, t1) => renderOp("+", t0, t1, sb)
    case Times(t0, t1) => renderOp("*", t0, t1, sb)
    case Div(t0, t1) => renderOp("div", t0, t1, sb)
    case Mod(t0, t1) => renderOp("mod", t0, t1, sb)

    /* Arithmetic comparisons */

    case Less(t0, t1) => renderOp("<", t0, t1, sb)
    case AtMost(t0, t1) => renderOp("<=", t0, t1, sb)
    case AtLeast(t0, t1) => renderOp(">=", t0, t1, sb)
    case Greater(t0, t1) => renderOp(">", t0, t1, sb)

    case _ => renderPermOrCollection(term, sb)
  }

  private def renderPermOrCollection(term: Term, sb: StringBuilder): Unit = term match {
    /* Permissions */

    case FullPerm => sb.append("$Perm.Write")
    case NoPerm => sb.append("$Perm.No")
    case FractionPermLiteral(r) => renderRealOp("/", IntLiteral(r.numerator), IntLiteral(r.denominator), sb)
    case FractionPerm(n, d) => renderRealOp("/", n, d, sb)
    case PermLess(t0, t1) => renderOp("<", t0, t1, sb)
    case PermAtMost(t0, t1) => renderOp("<=", t0, t1, sb)
    case PermPlus(t0, t1) => renderRealOp("+", t0, t1, sb)
    case PermMinus(t0, t1) => renderRealOp("-", t0, t1, sb)
    case PermTimes(t0, t1) => renderRealOp("*", t0, t1, sb)
    case IntPermTimes(t0, t1) => renderRealOp("*", t0, t1, sb)
    case PermIntDiv(t0, t1) => renderRealOp("/", t0, t1, sb)
    case PermPermDiv(t0, t1) => renderRealOp("/", t0, t1, sb)
    case PermMin(t0, t1) => renderOp("$Perm.min", t0, t1, sb)
    case IsValidPermVal(v) => renderOp("$Perm.isValidVar", v, sb)
    case IsReadPermVar(v) => renderOp("$Perm.isReadVar", v, sb)

    /* Sequences */

    case SeqRanged(t0, t1) => renderOp("Seq_range", t0, t1, sb)
    case SeqSingleton(t0) => renderOp("Seq_singleton", t0, sb)
    case SeqAppend(t0, t1) => renderOp("Seq_append", t0, t1, sb)
    case SeqLength(t0) => renderOp("Seq_length", t0, sb)
    case SeqAt(t0, t1) => renderOp("Seq_index", t0, t1, sb)
    case SeqTake(t0, t1) => renderOp("Seq_take", t0, t1, sb)
    case SeqDrop(t0, t1) => renderOp("Seq_drop", t0, t1, sb)
    case SeqIn(t0, t1) => renderOp("Seq_contains", t0, t1, sb)
    case SeqInTrigger(t0, t1) => renderOp("Seq_contains_trigger", t0, t1, sb)
    case SeqUpdate(t0, t1, t2) => renderOp("Seq_update", t0, t1, t2, sb)

    /* Sets */

    case uop: SingletonSet => renderApp("Set_singleton", Seq(uop.p), uop.sort, sb)
    case bop: SetAdd => renderApp("Set_unionone", Seq(bop.p0, bop.p1), bop.sort, sb)
    case uop: SetCardinality => renderApp("Set_card", Seq(uop.p), uop.sort, sb)
    case bop: SetDifference => renderApp("Set_difference", Seq(bop.p0, bop.p1), bop.sort, sb)
    case bop: SetIntersection => renderApp("Set_intersection", Seq(bop.p0, bop.p1), bop.sort, sb)
    case bop: SetUnion => renderApp("Set_union", Seq(bop.p0, bop.p1), bop.sort, sb)
    case bop: SetIn => renderApp("Set_in", Seq(bop.p0, bop.p1), bop.sort, sb)
    case bop: SetSubset => renderApp("Set_subset", Seq(bop.p0, bop.p1), bop.sort, sb)
    case bop: SetDisjoint => renderApp("Set_disjoint", Seq(bop.p0, bop.p1), bop.sort, sb)

    /* Multisets */

    case uop: SingletonMultiset => renderApp("Multiset_singleton", Seq(uop.p), uop.sort, sb)
    case bop: MultisetAdd => renderApp("Multiset_unionone", Seq(bop.p0, bop.p1), bop.sort, sb)
    case uop: MultisetCardinality => renderApp("Multiset_card", Seq(uop.p), uop.sort, sb)
    case bop: MultisetDifference => renderApp("Multiset_difference", Seq(bop.p0, bop.p1), bop.sort, sb)
    case bop: MultisetIntersection => renderApp("Multiset_intersection", Seq(bop.p0, bop.p1), bop.sort, sb)
    case bop: MultisetUnion => renderApp("Multiset_union", Seq(bop.p0, bop.p1), bop.sort, sb)
    case bop: MultisetSubset => renderApp("Multiset_subset", Seq(bop.p0, bop.p1), bop.sort, sb)
    case bop: MultisetCount => renderApp("Multiset_count", Seq(bop.p0, bop.p1), bop.sort, sb)

    /* Maps */

    case m: MapCardinality => renderApp("Map_card", Seq(m.p), m.sort, sb)
    case m: MapDomain => renderApp("Map_domain", Seq(m.p), m.sort, sb)
    case m: MapRange => renderApp("Map_values", Seq(m.p), m.sort, sb)
    case m: MapLookup => renderApp("Map_apply", Seq(m.p0, m.p1), m.sort, sb)
    case m: MapUpdate => renderApp("Map_update", Seq(m.base, m.key, m.value), m.sort, sb)

    case _ => renderHeapOrOther(term, sb)
  }

  private def renderHeapOrOther(term: Term, sb: StringBuilder): Unit = term match {
    /* Quantified Permissions */

    case Domain(id, fvf) => renderOp("$FVF.domain_" + id, fvf, sb)

    case HasDomain(id, fvf, _) => renderOp("$FVF.has_domain_" + id, fvf, sb)

    case Lookup(field, fvf, at) => renderOp("$FVF.lookup_" + field, fvf, at, sb)

    case FieldTrigger(field, fvf, at) =>
      fvf.sort match {
        case sorts.FieldValueFunction(_, _) => renderOp("$FVF.loc_" + field, Lookup(field, fvf, at), at, sb)
        case _ => renderOp("$FVF.loc_" + field, fvf, at, sb)
      }

    case PermLookup(field, pm, at) => renderOp("$FVF.perm_" + field, pm, at, sb)

    case HeapLookup(heap, at) =>
      openHeapOp("$Hp.get_", heap.sort, sb); renderArgs(heap, at, sb)

    case HeapUpdate(heap, at, value) =>
      openHeapOp("$Hp.update_", heap.sort, sb); renderArgs(heap, at, value, sb)

    case HeapSingleton(at, value, _) =>
      openHeapOp("$Hp.singleton_", term.sort, sb); renderArgs(at, value, sb)

    case IdenticalOnKnownLocations(oldHeap, newHeap, mask) =>
      openHeapOp("$Hp.identicalOnKnown_", newHeap.sort, sb); renderArgs(oldHeap, newHeap, mask, sb)

    case GoodMask(mask) => renderOp("$Hp.maskGood", mask, sb)

    case GoodFieldMask(mask, upper) =>
      sb.append("($Hp.maskGoodField ")
      renderTerm(mask, sb)
      sb.append(if (upper) " true)" else " false)")

    case DummyHeap(sort) =>
      sb.append("$Hp.default_")
      renderHeapType(sort, sb)

    case MergeSingle(heap, mask, location, value) =>
      openHeapOp("$Hp.merge_single_", heap.sort, sb); renderArgs(Seq(heap, mask, location, value), sb)

    case MaskEq(m1, m2) => renderOp("$Hp.maskEq", m1, m2, sb)
    case MaskSum(m1, m2) => renderOp("$Hp.maskSum", m1, m2, sb)
    case MaskDiff(m1, m2) => renderOp("$Hp.maskDiff", m1, m2, sb)
    case MaskAdd(m, at, value) => renderOp("$Hp.maskAdd", m, at, value, sb)

    case MergeHeaps(h1, m1, h2, m2) =>
      openHeapOp("$Hp.merge_", h1.sort, sb); renderArgs(Seq(h1, m1, h2, m2), sb)

    case HeapsOverlap(h1, m1, h2, m2) =>
      openHeapOp("$Hp.overlap_", h1.sort, sb); renderArgs(Seq(h1, m1, h2, m2), sb)

    case SnapToHeap(snap, resource, _) =>
      sb.append("($SortWrappers.$SnapTo$Heap<").append(resourceName(resource)).append('>')
      renderArgs(snap, sb)

    case HeapToSnap(heap, mask, resource) =>
      sb.append("($SortWrappers.$Heap<").append(resourceName(resource)).append(">To$Snap")
      renderArgs(heap, mask, sb)

    case PredicateDomain(id, psf) => renderOp("$PSF.domain_" + id, psf, sb)

    case HasPredicateDomain(id, psf, _) => renderOp("$PSF.has_domain_" + id, psf, sb)

    case PredicateLookup(id, psf, args) => renderOp("$PSF.lookup_" + id, psf, toSnapTree(args), sb)

    case PredicateTrigger(id, psf, args) =>
      renderOp("$PSF.loc_" + id, PredicateLookup(id, psf, args), toSnapTree(args), sb)

    case PredicatePermLookup(predname, pm, args) => renderOp("$PSF.perm_" + predname, pm, toSnapTree(args), sb)

    /* Other terms */

    case First(t) => renderOp("$Snap.first", t, sb)
    case Second(t) => renderOp("$Snap.second", t, sb)

    case Combine(t0, t1) => renderOp("$Snap.combine", t0, t1, sb)

    case SortWrapper(t, to) => renderOp(render(SortWrapperId(t.sort, to)), t, sb)

    case Distinct(symbols) =>
      sb.append("(distinct")
      symbols foreach (s => sb.append(' ').append(render(s.id)))
      sb.append(')')

    case Let(bindings, body) =>
      withoutSharing {
        sb.append("(let (")
        renderSeparated(bindings.toSeq, sb)((p, sb) => {
          sb.append('(').append(render(p._1.id)).append(' ')
          renderTerm(p._2, sb)
          sb.append(')')
        })
        sb.append(") ")
        renderTerm(body, sb)
        sb.append(')')
      }

    case MagicWandSnapshot(mwsf) => renderTerm(mwsf, sb)
    case MWSFLookup(mwsf, snap) => renderApp("MWSF_apply", Seq(mwsf, snap), sorts.Snap, sb)
    case MagicWandToken(mwsf, snapLhs) => renderApp("MW_token", Seq(mwsf, snapLhs), sorts.Bool, sb)

    case _: MagicWandChunkTerm
       | _: Quantification =>
      sys.error(s"Unexpected term $term cannot be translated to SMTLib code")
  }

  private def renderQuantification(q: Quantification, sb: StringBuilder): Unit = {
    val Quantification(quant, vars, body, triggers, name, _, weight) = q

    if (vars.nonEmpty) {
      sb.append('(').append(render(quant)).append(" (")
      renderSeparated(vars, sb)(renderVarDecl)
      sb.append(") ")

      // Omit annotation for empty name and triggers since cvc5 fails
      // for annotations containing zero attributes (Z3 simply ignores it).
      val annotate = !(name.isEmpty && triggers.isEmpty)
      if (annotate) sb.append("(! ")

      renderTerm(body, sb)

      // Render only triggers with non-empty terms since cvc5 breaks on
      // empty patterns (i.e. :pattern ()).
      if (annotate) {
        triggers foreach (trigger =>
          if (trigger.p.nonEmpty) {
            sb.append(" :pattern (")
            renderSeparated(trigger.p, sb)(renderTerm)
            sb.append(')')
          })

        if (name.nonEmpty) sb.append(" :qid |").append(name).append('|')
      }

      weight foreach (w => sb.append(" :weight ").append(w))

      if (annotate) sb.append(')')
      sb.append(')')
    } else {
      // TODO: This seems like a hack.
      //       It would be better to avoid creating quantifications with no variables in the first place.
      sb.append("; WARNING: Got invalid quantifier: ").append(q.toString).append('\n')
      renderTerm(body, sb)
    }
  }

  private def renderVarDecl(v: Var, sb: StringBuilder): Unit = {
    sb.append('(').append(render(v.id)).append(' ')
    renderSort(v.sort, false, sb)
    sb.append(')')
  }

  private def resourceName(resource: Any): String = resource match {
    case f: ast.Field => f.name
    case p: ast.Predicate => p.name
    case _: MagicWandIdentifier => "$MWSF"
  }

  @inline
  private def renderSeparated[A](as: Iterable[A], sb: StringBuilder)(renderOne: (A, StringBuilder) => Unit): Unit = {
    var first = true
    as foreach (a => {
      if (!first) sb.append(' ')
      first = false
      renderOne(a, sb)
    })
  }

  private def openHeapOp(op: String, heapSort: Sort, sb: StringBuilder): Unit = {
    sb.append('(').append(op)
    renderHeapType(heapSort, sb)
  }

  /* The renderArgs methods render ` arg1 ... argN)`, i.e. they also close the application. */

  private def renderArgs(t0: Term, sb: StringBuilder): Unit = {
    sb.append(' '); renderTerm(t0, sb)
    sb.append(')')
  }

  private def renderArgs(t0: Term, t1: Term, sb: StringBuilder): Unit = {
    sb.append(' '); renderTerm(t0, sb)
    sb.append(' '); renderTerm(t1, sb)
    sb.append(')')
  }

  private def renderArgs(t0: Term, t1: Term, t2: Term, sb: StringBuilder): Unit = {
    sb.append(' '); renderTerm(t0, sb)
    sb.append(' '); renderTerm(t1, sb)
    sb.append(' '); renderTerm(t2, sb)
    sb.append(')')
  }

  private def renderArgs(ts: Iterable[Term], sb: StringBuilder): Unit = {
    ts foreach (t => { sb.append(' '); renderTerm(t, sb) })
    sb.append(')')
  }

  protected def renderOp(op: String, t0: Term, sb: StringBuilder): Unit = {
    sb.append('(').append(op)
    renderArgs(t0, sb)
  }

  protected def renderOp(op: String, t0: Term, t1: Term, sb: StringBuilder): Unit = {
    sb.append('(').append(op)
    renderArgs(t0, t1, sb)
  }

  protected def renderOp(op: String, t0: Term, t1: Term, t2: Term, sb: StringBuilder): Unit = {
    sb.append('(').append(op)
    renderArgs(t0, t1, t2, sb)
  }

  protected def renderOp(op: String, ts: Iterable[Term], sb: StringBuilder): Unit = {
    sb.append('(').append(op)
    renderArgs(ts, sb)
  }

  protected def renderRealOp(op: String, t0: Term, t1: Term, sb: StringBuilder): Unit = {
    sb.append('(').append(op).append(' ')
    renderAsReal(t0, sb)
    sb.append(' ')
    renderAsReal(t1, sb)
    sb.append(')')
  }

  protected def renderApp(functionName: String, args: Seq[Term], outSort: Sort, sb: StringBuilder): Unit = {
    if (args.nonEmpty) {
      renderOp(sanitize(functionName), args, sb)
    } else {
      sb.append("(as ").append(sanitize(functionName)).append(' ')
      renderSort(outSort, false, sb)
      sb.append(')')
    }
  }

  protected def renderSMTApp(functionName: String, args: Seq[Term], sb: StringBuilder): Unit = {
    if (args.nonEmpty) renderOp(functionName, args, sb)
    else sb.append(functionName)
  }

  protected def render(q: Quantifier): String = q match {
    case Forall => "forall"
    case Exists => "exists"
  }

  protected def renderLiteral(literal: Literal, sb: StringBuilder): Unit = literal match {
    case IntLiteral(n) =>
      if (n >= 0) sb.append(n.toString())
      else sb.append("(- 0 ").append((-n).toString()).append(')')

    case Unit => sb.append("$Snap.unit")
    case ZeroMask => sb.append("$Hp.zeroMask")
    case PredZeroMask => sb.append("$Hp.zeroPredMask")
    case True => sb.append("true")
    case False => sb.append("false")
    case Null => sb.append("$Ref.null")
    case _: SeqNil => renderApp("Seq_empty", Seq(), literal.sort, sb)
    case _: EmptySet => renderApp("Set_empty", Seq(), literal.sort, sb)
    case _: EmptyMultiset => renderApp("Multiset_empty", Seq(), literal.sort, sb)
    case _: EmptyMap => renderApp("Map_empty", Seq(), literal.sort, sb)
  }

  protected def renderAsReal(t: Term, sb: StringBuilder): Unit =
    if (t.sort == sorts.Int) renderOp("to_real", t, sb)
    else renderTerm(t, sb)

  def render(id: Identifier, sanitizeIdentifier: Boolean = true): String = {
    val repr: String = id match {
      case SimpleIdentifier(name) => name
      case SuffixedIdentifier(prefix, separator, suffix) => s"${render(prefix, false)}$separator$suffix"
      case SortBasedIdentifier(template, sorts) => template.format(sorts.map(convert): _*)
    }

    if (sanitizeIdentifier) sanitize(repr)
    else repr
  }

  private def sanitize(str: String): String = {
    val sanitizedName = sanitizedNamesCache.getOrElseUpdate(str, nameSanitizer.sanitize(str))

    sanitizedName
  }

  def start(): Unit = {
    sanitizedNamesCache = mutable.Map.empty
  }

  def reset(): Unit = {
    sanitizedNamesCache.clear()
  }

  def stop(): Unit = {
    if (sanitizedNamesCache != null) sanitizedNamesCache.clear()
  }
}

private final class SharingInfo {
  /* Number of occurrences in the term, counting each occurrence of a parent node only once */
  var count: Int = 1
  /* Number of nodes in the term's tree, saturating at Long.MaxValue */
  var treeSize: Long = 1
  /* For bound nodes: the binding level (bound nodes only refer to bound nodes of lower levels), and the let name */
  var level: Int = 0
  var name: String = _
  /* See SharingAnalysis.analyse */
  var up: Int = 0
}

/** Decides which subterms of a term to bind with `let`s.
  *
  * Silicon's terms are trees in which the same subterm is often repeated; e.g. permission amounts of the shape
  * `p - min(p, q)` nest repeatedly when chunks are consolidated, and rendering such a term naively produces text
  * exponential in its nesting depth. The analysis finds the distinct (structurally equal) subterms and their number of
  * occurrences in the term's DAG. Subterms that occur more than once are bound by a `let`.
  *
  * The analysis does not descend into binders (quantifiers and lets), so the bound subterms contain no bound variables
  * and are rendered in the outermost scope.
  */
private object SharingAnalysis {
  /* Sharing is only used for terms that are huge as trees and shrink a lot as DAGs. Lets change the order in which
   * Z3 creates terms, which perturbs its search: lower thresholds (1000 nodes, factor 2 or 10) made some verifying
   * programs time out or fail. */
  private val minTreeSize = 10000
  private val minCompressionFactor = 10

  private def isShareable(t: Term): Boolean = t match {
    case hvr: HasVarRepr if hvr.varRepr.isDefined => false
    case _: Symbol | _: Literal | _: PermLiteral | _: Quantification | _: Let | _: MagicWandChunkTerm => false
    case app: Application[_] => app.args.nonEmpty
    case _ => true
  }

  /* Without mutable aliases, structurally equal terms have equal unfolded sizes under isShareable's
   * leaf boundaries. Identity memoization therefore computes the same size as visit, saturated at
   * minTreeSize, counting the cached size once per incoming edge (not once per identity).
   * Alias state is not part of structural equality: equal parents can hide differently aliased
   * children. On encountering an alias we conservatively defer to visit instead of undercounting
   * a subtree whose structural representative might be unaliased. We never descend into aliases
   * or binders. A saturated subtree or minTreeSize-deep path already proves the root large enough.
   */
  private[decider] def mayHaveLargeTree(root: Term): Boolean = {
    final class Frame(val term: Term) {
      val children: Iterator[Term] = viper.silicon.state.utils.subterms(term).iterator
      var size: Int = 1
    }
    val sizes = new java.util.IdentityHashMap[Term, java.lang.Integer]()
    val stack = new java.util.ArrayDeque[Frame]()
    if (!isShareable(root)) return false
    stack.push(new Frame(root))
    while (!stack.isEmpty) {
      val frame = stack.peek()
      if (frame.children.hasNext) {
        val child = frame.children.next()
        child match {
          case hvr: HasVarRepr if hvr.varRepr.isDefined => return true
          case _ =>
        }
        val cached = sizes.get(child)
        if (!isShareable(child)) frame.size += 1
        else if (cached != null) frame.size = math.min(minTreeSize, frame.size + cached.intValue())
        else {
          if (stack.size() >= minTreeSize - 1) return true
          stack.push(new Frame(child))
        }
      } else {
        sizes.put(frame.term, frame.size)
        stack.pop()
        if (!stack.isEmpty) {
          val parent = stack.peek()
          parent.size = math.min(minTreeSize, parent.size + frame.size)
        }
      }
      if (!stack.isEmpty && stack.peek().size >= minTreeSize) return true
    }
    false
  }

  /** Returns None if the term should be rendered without sharing, and otherwise the bound subterms and, for each
    * binding level, the subterms bound on that level, in an order in which subterms precede their superterms.
    */
  def analyse(root: Term): Option[(java.util.HashMap[Term, SharingInfo], Array[mutable.ArrayBuffer[Term]])] = {
    if (!isShareable(root) || !mayHaveLargeTree(root)) return None

    val infos = new java.util.HashMap[Term, SharingInfo]()
    val postOrder = mutable.ArrayBuffer.empty[Term]

    def visit(t: Term): Long = {
      if (!isShareable(t)) return 1

      val existing = infos.get(t)
      if (existing != null) {
        existing.count += 1
        return existing.treeSize
      }

      val info = new SharingInfo
      infos.put(t, info)
      var size = 1L
      viper.silicon.state.utils.subterms(t) foreach (c => {
        val s = visit(c)
        size = if (size > Long.MaxValue - s) Long.MaxValue else size + s
      })
      info.treeSize = size
      postOrder += t
      size
    }

    val treeSize = visit(root)
    if (treeSize < minTreeSize || treeSize / infos.size < minCompressionFactor) return None

    /* `up` of a node is the minimal level at which a let may refer to the node's rendering: one above the level of a
     * bound node, and the maximum over the children for other nodes. A bound node is bound at the maximum `up` of its
     * children, so it only refers to names bound at lower levels. */
    var levelCount = 0
    var nextName = 0
    postOrder foreach (t => {
      val info = infos.get(t)
      var below = 0
      viper.silicon.state.utils.subterms(t) foreach (c => {
        val ci = infos.get(c)
        if (ci != null && ci.up > below) below = ci.up
      })
      if (info.count > 1 && (t ne root)) {
        info.name = "$t!" + nextName
        nextName += 1
        info.level = below
        info.up = below + 1
        if (info.up > levelCount) levelCount = info.up
      } else {
        info.up = below
      }
    })

    val levels = Array.fill(levelCount)(mutable.ArrayBuffer.empty[Term])
    postOrder foreach (t => {
      val info = infos.get(t)
      if (info.name != null) levels(info.level) += t
    })

    Some((infos, levels))
  }
}
