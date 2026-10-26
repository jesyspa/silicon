// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2019 ETH Zurich.

package viper.silicon.logger.renderer

import viper.silicon.logger.MemberSymbExLog
import viper.silicon.logger.records.SymbolicRecord
import viper.silicon.logger.records.data.DataRecord
import viper.silicon.logger.records.scoping.{CloseScopeRecord, OpenScopeRecord}
import viper.silicon.logger.records.structural.{BranchingRecord, JoiningRecord}
import viper.silicon.state.terms.Not

class SimpleTreeRenderer extends Renderer[MemberSymbExLog, String] {
  def render(memberList: Iterable[MemberSymbExLog]): String = {
    val result = new StringBuilder
    memberList.foreach { member =>
      result.append(renderMember(member)).append('\n')
    }
    result.toString
  }

  def renderMember(member: MemberSymbExLog): String = {
    val result = new StringBuilder
    appendTree(member.log, result)
    result.toString
  }

  private sealed trait Frame
  private final class Records(val records: Iterator[SymbolicRecord], val minIndent: Int,
                              var indent: Int) extends Frame
  private final class Branches(val record: BranchingRecord, val indent: Int,
                               var index: Int = 0) extends Frame

  /** Append directly to one output buffer. Explicit continuation frames keep deeply nested branch points off the
    * JVM stack, and preserve the enclosing scope's indentation when a branch finishes. */
  private def appendTree(log: Seq[SymbolicRecord], result: StringBuilder): Unit = {
    var pending: List[Frame] = new Records(log.iterator, 0, 0) :: Nil
    while (pending.nonEmpty) {
      pending.head match {
        case frame: Records =>
          if (!frame.records.hasNext) {
            pending = pending.tail
          } else {
            frame.records.next() match {
              case _: OpenScopeRecord => frame.indent += 1
              case _: CloseScopeRecord => frame.indent = Math.max(frame.minIndent, frame.indent - 1)
              case br: BranchingRecord => pending = new Branches(br, frame.indent) :: pending
              case _: JoiningRecord =>
                appendIndent(result, frame.indent)
                result.append("Join\n")
              case dr: DataRecord =>
                appendIndent(result, frame.indent)
                result.append(dr.toString).append('\n')
            }
          }

        case frame: Branches =>
          val branches = frame.record.getBranchInfos
          if (frame.index == branches.length) {
            pending = pending.tail
          } else {
            val index = frame.index
            frame.index += 1
            appendIndent(result, frame.indent)
            result.append("Branch ")
            if (branches.length <= 2 && frame.record.condition.isDefined) {
              val condition = if (index == 0) frame.record.condition.get else Not(frame.record.condition.get)
              result.append(condition)
            } else {
              result.append(index + 1)
            }
            result.append(":\n")
            appendIndent(result, frame.indent + 1)
            val branch = branches(index)
            if (branch.isReachable) {
              result.append("comment: Reachable\n")
              pending = new Records(branch.records.iterator, frame.indent + 1, frame.indent + 1) :: pending
            } else {
              result.append("comment: Unreachable\n")
            }
          }
      }
    }
  }

  private def appendIndent(result: StringBuilder, level: Int): Unit = {
    var i = 0
    while (i < level) {
      result.append("  ")
      i += 1
    }
  }
}
