// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 ETH Zurich.

package viper.silicon.tests

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Config
import viper.silicon.logger.{LogConfig, MemberSymbExLog}
import viper.silicon.logger.records.SymbolicRecord
import viper.silicon.logger.records.data.{CommentRecord, DataRecord}
import viper.silicon.logger.records.scoping.{CloseScopeRecord, OpenScopeRecord}
import viper.silicon.logger.records.structural.{BranchingRecord, JoiningRecord}
import viper.silicon.logger.renderer.SimpleTreeRenderer
import viper.silicon.logger.writer.SymbExLogReportWriter
import viper.silicon.state.SimpleIdentifier
import viper.silicon.state.terms.{Not, Term, Var, sorts}
import viper.silicon.verifier.Verifier

/** The text and JSON exports of symbolic execution logs keep their exact output, also for deep and wide logs. */
class SymbExLogExportTests extends AnyFunSuite with BeforeAndAfterAll {
  private var nextId = 0
  private var previousConfig: Config = _

  /* Terms in branch conditions read the configuration */
  override def beforeAll(): Unit = {
    previousConfig = Verifier.config
    Verifier.config = new Config(Seq("dummy.sil"))
  }

  override def afterAll(): Unit = Verifier.config = previousConfig

  private def withId[R <: SymbolicRecord](record: R): R = {
    record.id = nextId
    nextId += 1
    record
  }

  private def comment(text: String): CommentRecord = withId(new CommentRecord(text, null, null))
  private def open(ref: DataRecord): OpenScopeRecord = withId(new OpenScopeRecord(ref))
  private def close(ref: DataRecord): CloseScopeRecord = withId(new CloseScopeRecord(ref.id))
  private def join(): JoiningRecord = withId(new JoiningRecord(null, null))
  private def condition(name: String): Term = Var(SimpleIdentifier(name), sorts.Bool, false)

  /* `branches` gives each branch's records, or None for an unreachable branch that still holds records */
  private def branching(cond: Option[Term], branches: Seq[(Boolean, Seq[SymbolicRecord])]): BranchingRecord = {
    val record = withId(new BranchingRecord(branches.length, cond, None))
    record.getBranchInfos.zip(branches).foreach { case (info, (reachable, records)) =>
      info.isReachable = reachable
      info.records = records
    }
    record
  }

  private def member(records: Seq[SymbolicRecord]): MemberSymbExLog = {
    val log = new MemberSymbExLog(null, LogConfig.default(), null, null)
    log.log = records.toVector
    log
  }

  private def shallowLog(): Seq[SymbolicRecord] = {
    nextId = 0
    val outer = comment("outer")
    val inner = comment("inner")
    val nested = comment("nested")
    Vector(
      outer,
      open(outer),
      branching(Some(condition("b")), Seq(
        true -> Vector(inner, open(inner), comment("in branch"), close(inner), close(inner), close(inner), comment("after underflow")),
        false -> Vector(comment("hidden")))),
      join(),
      branching(None, Seq(
        true -> Vector(nested),
        false -> Vector(),
        true -> Vector(branching(Some(condition("c")), Seq(true -> Vector(comment("deep")), true -> Vector()))))),
      close(outer),
      comment("end"))
  }

  test("Text rendering of a shallow log is unchanged") {
    val expected =
      """comment outer
        |  Branch b:
        |    comment: Reachable
        |    comment inner
        |      comment in branch
        |    comment after underflow
        |  Branch !(b):
        |    comment: Unreachable
        |  Join
        |  Branch 1:
        |    comment: Reachable
        |    comment nested
        |  Branch 2:
        |    comment: Unreachable
        |  Branch 3:
        |    comment: Reachable
        |    Branch c:
        |      comment: Reachable
        |      comment deep
        |    Branch !(c):
        |      comment: Reachable
        |comment end
        |""".stripMargin
    val log = member(shallowLog())
    assert(new SimpleTreeRenderer().renderMember(log) == expected)
    assert(new SimpleTreeRenderer().render(Seq(log, log)) == expected + "\n" + expected + "\n")
  }

  test("JSON export of a shallow log is unchanged") {
    val expected = GoldenJson.shallow
    val log = member(shallowLog())
    assert(SymbExLogReportWriter.toJSON(Seq(log, log), LogConfig.default()).compactPrint == expected)
    assert(SymbExLogReportWriter.getAllRecords(log.log).map(_.id) ==
      Seq(0, 3, 11, 1, 4, 5, 6, 7, 8, 9, 10, 12, 15, 2, 14, 13, 16, 17))
  }

  /* The recursive renderer and flattening before they were made stack-safe, kept as the reference */
  private object Reference {
    def render(log: Seq[SymbolicRecord], minN: Int, n: Int): String = {
      var res = ""
      var indentLevel = n
      for (record <- log) {
        record match {
          case _: OpenScopeRecord => indentLevel = indentLevel + 1
          case _: CloseScopeRecord => indentLevel = Math.max(minN, indentLevel - 1)
          case br: BranchingRecord => res = res + render(br, indentLevel)
          case _: JoiningRecord => res = res + s"${indent(indentLevel)}Join\n"
          case dr: DataRecord => res = res + s"${indent(indentLevel)}$dr\n"
        }
      }
      res
    }

    private def indent(level: Int): String = "  " * level

    private def render(br: BranchingRecord, n: Int): String = {
      var res = ""
      val branches = br.getBranches
      for (branchIndex <- branches.indices) {
        if (branches.length <= 2 && br.condition.isDefined) {
          val condition = if (branchIndex == 0) br.condition.get else Not(br.condition.get)
          res = s"$res${indent(n)}Branch $condition:\n"
        } else {
          res = s"$res${indent(n)}Branch ${branchIndex + 1}:\n"
        }
        if (br.isReachable(branchIndex)) {
          res = s"$res${indent(n + 1)}comment: Reachable\n"
          res = res + render(branches(branchIndex), n + 1, n + 1)
        } else {
          res = s"$res${indent(n + 1)}comment: Unreachable\n"
        }
      }
      res
    }

    def allRecords(logs: Seq[SymbolicRecord]): Seq[SymbolicRecord] =
      logs.foldLeft(Vector[SymbolicRecord]())((prev, record) => prev ++ allRecords(record))

    private def allRecords(r: SymbolicRecord): Seq[SymbolicRecord] = r match {
      case br: BranchingRecord => br.getBranches.foldLeft(Vector[SymbolicRecord](br))((prev, branch) => prev ++ allRecords(branch))
      case _ => Vector(r)
    }
  }

  /* Nested branch points, each holding scopes, data, an unreachable sibling and a join after the branch */
  private def deepLog(depth: Int): Seq[SymbolicRecord] = {
    nextId = 0
    var inner: Vector[SymbolicRecord] = Vector(comment("leaf"))
    for (level <- depth - 1 to 0 by -1) {
      val head = comment(s"level $level")
      val cond = if (level % 3 == 2) None else Some(condition(s"c$level"))
      inner = Vector(
        head, open(head),
        branching(cond, Seq(true -> inner, (level % 2 == 0) -> Vector(comment(s"sibling $level")))),
        join(), close(head), close(head))
    }
    inner
  }

  private def wideLog(width: Int): Seq[SymbolicRecord] = {
    nextId = 0
    (0 until width).flatMap { i =>
      val record = comment(s"record $i")
      if (i % 10 == 0) Vector(record, branching(Some(condition(s"w$i")), Seq(true -> Vector(comment(s"in $i")), false -> Vector(comment(s"out $i")))))
      else if (i % 7 == 0) Vector(record, open(record))
      else if (i % 11 == 0) Vector(close(record), record)
      else Vector(record)
    }
  }

  private def assertMatchesReference(records: Seq[SymbolicRecord]): Unit = {
    val log = member(records)
    assert(new SimpleTreeRenderer().renderMember(log) == Reference.render(records, 0, 0))
    assert(SymbExLogReportWriter.getAllRecords(records).map(_.id) == Reference.allRecords(records).map(_.id))
    assert(SymbExLogReportWriter.toJSON(Seq(log), LogConfig.default()) ==
      spray.json.JsArray(Reference.allRecords(records).map(SymbExLogReportWriter.toJSON(_, LogConfig.default())).toVector))
  }

  test("Deep logs match the reference export") {
    assertMatchesReference(deepLog(200))
  }

  test("Wide logs match the reference export") {
    assertMatchesReference(wideLog(3000))
  }

  /* Runs the body on a thread with a small stack, so that recursion proportional to the nesting depth fails */
  private def onSmallStack[R](body: => R): R = {
    var result: Option[R] = None
    var failure: Throwable = null
    val thread = new Thread(null, () => {
      try result = Some(body)
      catch { case t: Throwable => failure = t }
    }, "small-stack", 256 * 1024)
    thread.start()
    thread.join()
    failure match {
      case null => result.get
      // ScalaTest aborts the whole suite on a StackOverflowError, so report it as a failed test.
      case overflow: StackOverflowError => fail("the export overflowed the stack", overflow)
      case other => throw other
    }
  }

  test("Very deep logs export to JSON without overflowing the stack") {
    val depth = 20000
    val log = member(deepLog(depth))
    val exported = onSmallStack(SymbExLogReportWriter.toJSON(Seq(log), LogConfig.default()))
    assert(exported.elements.length == 7 * depth + 1)
  }

  test("Deeply nested logs render as text without overflowing the stack") {
    // The indentation makes the text quadratic in the depth, which bounds the depth tested here.
    val depth = 1500
    val log = member(deepLog(depth))
    val text = onSmallStack(new SimpleTreeRenderer().renderMember(log))
    assert(text.count(_ == '\n') == 6 * depth + (depth + 1) / 2 + 1)
    assert(text.contains("\n" + "  " * (2 * depth) + "comment leaf\n"))
  }
}

private object GoldenJson {
  /* The export of [[SymbExLogExportTests.shallowLog]] before the export was made stack-safe */
  private val member: String =
    """{"id":0,"kind":"comment","value":"outer"},{"id":3,"kind":"open","value":"0","isScopeOpen":true,"data":{"refId":0,"timeMs":0}},{"id":11,"kind":"branching","value":"b","branches":[{"isReachable":true,"records":[1,4,5,6,7,8,9],"startTimeMs":0},{"isReachable":false,"records":[10],"startTimeMs":0}]},{"id":1,"kind":"comment","value":"inner"},{"id":4,"kind":"open","value":"1","isScopeOpen":true,"data":{"refId":1,"timeMs":0}},{"id":5,"kind":"comment","value":"in branch"},{"id":6,"kind":"close","value":"1","isScopeClose":true,"data":{"refId":1,"timeMs":0}},{"id":7,"kind":"close","value":"1","isScopeClose":true,"data":{"refId":1,"timeMs":0}},{"id":8,"kind":"close","value":"1","isScopeClose":true,"data":{"refId":1,"timeMs":0}},{"id":9,"kind":"comment","value":"after underflow"},{"id":10,"kind":"comment","value":"hidden"},{"id":12,"kind":"joining","value":"null","isJoinPoint":true},{"id":15,"kind":"branching","value":"null","branches":[{"isReachable":true,"records":[2],"startTimeMs":0},{"isReachable":false,"records":[],"startTimeMs":0},{"isReachable":true,"records":[14],"startTimeMs":0}]},{"id":2,"kind":"comment","value":"nested"},{"id":14,"kind":"branching","value":"c","branches":[{"isReachable":true,"records":[13],"startTimeMs":0},{"isReachable":true,"records":[],"startTimeMs":0}]},{"id":13,"kind":"comment","value":"deep"},{"id":16,"kind":"close","value":"0","isScopeClose":true,"data":{"refId":0,"timeMs":0}},{"id":17,"kind":"comment","value":"end"}"""

  val shallow: String = s"[$member,$member]"
}
