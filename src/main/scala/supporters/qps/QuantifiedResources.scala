// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.supporters.qps

import java.util.IdentityHashMap
import scala.collection.mutable
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silver.ast._
import viper.silver.ast.utility.Nodes
import viper.silver.ast.utility.QuantifiedPermissions.QuantifiedPermissionAssertion

private[silicon] object QuantifiedResources {
  final case class ForMember(fields: InsertionOrderedSet[Field],
                             predicates: InsertionOrderedSet[Predicate],
                             wands: Set[MagicWand],
                             triggers: InsertionOrderedSet[Resource])

  final case class ForPreamble(fields: InsertionOrderedSet[Field],
                               predicates: InsertionOrderedSet[Predicate],
                               wands: Set[MagicWand])

  private class Scan(root: Node, program: Program, deepTriggers: Boolean) {
    val fields = mutable.LinkedHashSet.empty[Field]
    val predicates = mutable.LinkedHashSet.empty[Predicate]
    val triggers = mutable.LinkedHashSet.empty[Resource]
    private val wandGroups = Vector.newBuilder[Seq[MagicWand]]

    private def recordTriggers(ts: Seq[Trigger], skipFields: Boolean, skipPredicates: Boolean, skipWands: Boolean): Unit = {
      val wands = Vector.newBuilder[MagicWand]
      ts.foreach(_.exps.foreach { exp =>
        if (deepTriggers) {
          exp.visit {
            case fa: FieldAccess if !skipFields => fields += fa.field
            case pa: PredicateAccess if !skipPredicates => predicates += pa.loc(program)
          }
        } else {
          exp match {
            case fa: FieldAccess if !skipFields => fields += fa.field
            case pa: PredicateAccess if !skipPredicates => predicates += pa.loc(program)
            case _ =>
          }
          exp match {
            case access: ResourceAccess => triggers += access.res(program)
            case _ =>
          }
        }
        if (!skipWands) exp match {
          case wand: MagicWand => wands += wand
          case _ =>
        }
      })
      if (!skipWands) wandGroups += wands.result()
    }

    root.visit {
      case q: Forall => q match {
        // For each kind, a matching permission body takes precedence over triggers
        // for that kind only. Resource triggers are always collected.
        case QuantifiedPermissionAssertion(_, _, acc: FieldAccessPredicate) =>
          fields += acc.loc.field
          recordTriggers(q.triggers, skipFields = true, skipPredicates = false, skipWands = false)
        case QuantifiedPermissionAssertion(_, _, acc: PredicateAccessPredicate) =>
          predicates += program.findPredicate(acc.loc.predicateName)
          recordTriggers(q.triggers, skipFields = false, skipPredicates = true, skipWands = false)
        case QuantifiedPermissionAssertion(_, _, wand: MagicWand) =>
          wandGroups += Seq(wand)
          recordTriggers(q.triggers, skipFields = false, skipPredicates = false, skipWands = true)
        case _ => recordTriggers(q.triggers, skipFields = false, skipPredicates = false, skipWands = false)
      }
      case q: Exists => recordTriggers(q.triggers, skipFields = false, skipPredicates = false, skipWands = false)
    }

    // Preserve the wand collector's set-of-trigger-groups iteration order.
    def wands: Set[MagicWand] = wandGroups.result().iterator.map(_.map(_.structure(program))).toSet.flatten
  }

  def preamble(program: Program): ForPreamble = {
    val scan = new Scan(program, program, deepTriggers = true)
    ForPreamble(InsertionOrderedSet(scan.fields), InsertionOrderedSet(scan.predicates), scan.wands)
  }
}

/** Quantified resources for the rewritten program, shared within one verification. */
private[silicon] final class QuantifiedResources(program: Program) {
  import QuantifiedResources._

  lazy val preamble: ForPreamble = QuantifiedResources.preamble(program)

  private class Direct(node: Node) {
    val scan = new Scan(node, program, deepTriggers = false)
    lazy val references: Seq[Member] = Nodes.referencedMembers(node, program)
  }

  private val direct = new IdentityHashMap[Node, Direct]()
  private val members = new IdentityHashMap[Member, ForMember]()

  private def facts(node: Node): Direct = direct.computeIfAbsent(node, n => new Direct(n))

  def forMember(root: Member): ForMember = members.computeIfAbsent(root, member => {
    val fields = mutable.LinkedHashSet.empty[Field]
    val predicates = mutable.LinkedHashSet.empty[Predicate]
    val triggers = mutable.LinkedHashSet.empty[Resource]
    val visited = mutable.HashSet.empty[Member]
    val pending = mutable.Queue(member)
    // Seed with full-root references before walking callee specifications, as in Silver.
    pending ++= facts(member).references
    while (pending.nonEmpty) {
      val current = pending.dequeue()
      val nodes: Seq[Node] = current match {
        case m: Method if m != member => m.pres ++ m.posts
        case f: Function if f != member => f.pres ++ f.posts
        case _ => Seq(current)
      }
      visited += current
      nodes.foreach { node =>
        val data = facts(node)
        fields ++= data.scan.fields
        predicates ++= data.scan.predicates
        triggers ++= data.scan.triggers
        pending ++= data.references.iterator.filterNot(visited.contains)
      }
    }

    // Wands, unlike the other member resources, do not include dependencies.
    ForMember(InsertionOrderedSet(fields), InsertionOrderedSet(predicates), facts(member).scan.wands,
      InsertionOrderedSet(triggers))
  })
}
