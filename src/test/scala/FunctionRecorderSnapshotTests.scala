// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 ETH Zurich.

package viper.silicon.supporters.functions

import scala.util.Random
import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.{Config, Stack}
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.state.SimpleIdentifier
import viper.silicon.state.terms._
import viper.silicon.verifier.Verifier
import viper.silver.ast

/** The snapshot terms of recorded heap accesses agree with the original recursive construction. */
class FunctionRecorderSnapshotTests extends AnyFunSuite {
  private val loc: ast.LocationAccess =
    ast.FieldAccess(ast.LocalVar("x", ast.Ref)(), ast.Field("f", ast.Int)())()

  private def guard(name: String): Term = Var(SimpleIdentifier(name), sorts.Bool, false)
  private def snap(name: String): Term = Var(SimpleIdentifier(name), sorts.Snap, false)

  private def emptyRecorder: ActualFunctionRecorder =
    ActualFunctionRecorder(Right((ast.Predicate("P", Seq(), None)(), Seq())))

  private def record(recordings: Seq[(Stack[Term], Term)]): ActualFunctionRecorder =
    recordings.foldLeft(emptyRecorder) { case (recorder, (guards, value)) => recorder.recordSnapshot(loc, guards, value) }

  /* The construction before it was made stack-safe, kept as the reference */
  private def referenceTerm(snaps: InsertionOrderedSet[(Stack[Term], Term)], alternative: Option[Term]): Term = {
    assert(snaps.nonEmpty)
    if (snaps.size == 1) {
      alternative match {
        case Some(alt) => Ite(And(snaps.head._1.toSet), snaps.head._2, alt)
        case None => snaps.head._2
      }
    } else {
      if (snaps.head._1.isEmpty) {
        snaps.head._2
      } else {
        val firstBranch = snaps.head._1.head
        val grouped = snaps.groupBy(sn => {
          if (sn._1.nonEmpty && sn._1.head == firstBranch)
            1 // starting with firstBranch
          else if (sn._1.nonEmpty && sn._1.head == Not(firstBranch))
            2 // starting with Not(firstBranch)
          else
            3 // starting with other condition
        })

        def dropFirst(part: InsertionOrderedSet[(Stack[Term], Term)]): InsertionOrderedSet[(Stack[Term], Term)] = {
          part.map(sn => (sn._1.tail, sn._2))
        }

        val newAlt = if (grouped.contains(3)) Some(referenceTerm(grouped(3), alternative)) else alternative
        if (grouped.contains(1) && grouped.contains(2)) {
          val left = referenceTerm(dropFirst(grouped(1)), newAlt)
          val right = referenceTerm(dropFirst(grouped(2)), newAlt)
          Ite(firstBranch, left, right)
        } else {
          if (grouped.contains(1)) {
            newAlt match {
              case Some(actAlt) => Ite(firstBranch, referenceTerm(dropFirst(grouped(1)), newAlt), actAlt)
              case None => referenceTerm(dropFirst(grouped(1)), newAlt)
            }
          } else {
            newAlt match {
              case Some(actAlt) => Ite(Not(firstBranch), referenceTerm(dropFirst(grouped(2)), newAlt), actAlt)
              case None => referenceTerm(dropFirst(grouped(2)), newAlt)
            }
          }
        }
      }
    }
  }

  private def assertMatchesReference(recorder: ActualFunctionRecorder): Unit = {
    val key = (loc, Seq.empty[ExpContext])
    val expected = referenceTerm(recorder.locToSnaps(key), None)
    assert(recorder.locToSnap(key) == expected)
  }

  private def check(recordings: Seq[(Stack[Term], Term)]): Unit = assertMatchesReference(record(recordings))

  /* Runs the body with structural and with flyweight (reference) term equality */
  private def inBothTermModes(body: => Unit): Unit = {
    val previous = Verifier.config
    try {
      for (args <- Seq(Seq("dummy.sil"), Seq("--prover", "Z3-API", "dummy.sil"))) {
        Verifier.config = new Config(args)
        withClue(s"with ${args.mkString(" ")}: ")(body)
      }
    } finally {
      Verifier.config = previous
    }
  }

  test("Complementary guards in both polarities") { inBothTermModes {
    val (a, b) = (guard("a"), guard("b"))
    check(Seq(Seq(a) -> snap("s1"), Seq(Not(a)) -> snap("s2")))
    check(Seq(Seq(Not(a)) -> snap("s1"), Seq(a) -> snap("s2")))
    check(Seq(Seq(a, b) -> snap("s1"), Seq(a, Not(b)) -> snap("s2"), Seq(Not(a), b) -> snap("s3")))
    check(Seq(Seq(a, Not(b)) -> snap("s1"), Seq(Not(a)) -> snap("s2"), Seq(a, b) -> snap("s3")))
  }}

  test("Unrelated guards become fallbacks") { inBothTermModes {
    val (a, b, c) = (guard("a"), guard("b"), guard("c"))
    check(Seq(Seq(a) -> snap("s1"), Seq(b) -> snap("s2")))
    check(Seq(Seq(a) -> snap("s1"), Seq(b) -> snap("s2"), Seq(Not(a)) -> snap("s3")))
    check(Seq(Seq(a, c) -> snap("s1"), Seq(b, c) -> snap("s2"), Seq(c) -> snap("s3"), Seq(Not(a), b) -> snap("s4")))
    check(Seq(Seq(a, b) -> snap("s1"), Seq(c) -> snap("s2"), Seq(a, Not(b)) -> snap("s3")))
  }}

  test("Empty guard stacks and identical paths") { inBothTermModes {
    val (a, b) = (guard("a"), guard("b"))
    check(Seq(Seq() -> snap("s1")))
    check(Seq(Seq() -> snap("s1"), Seq(a) -> snap("s2")))
    check(Seq(Seq(a) -> snap("s1"), Seq() -> snap("s2"), Seq(Not(a)) -> snap("s3")))
    check(Seq(Seq(a, b) -> snap("s1"), Seq(a) -> snap("s2"), Seq(a, Not(b)) -> snap("s3")))
    // A repeated recording is kept once; the same path may also map to different snapshots.
    check(Seq(Seq(a) -> snap("s1"), Seq(a) -> snap("s1"), Seq(Not(a)) -> snap("s2")))
    check(Seq(Seq(a) -> snap("s1"), Seq(a) -> snap("s2"), Seq(Not(a)) -> snap("s3")))
    check(Seq(Seq(a, b) -> snap("s1"), Seq(a, b) -> snap("s2")))
  }}

  test("Merging recorders keeps the recording order") { inBothTermModes {
    val (a, b) = (guard("a"), guard("b"))
    val left = record(Seq(Seq(a, b) -> snap("s1"), Seq(b) -> snap("s2")))
    val right = record(Seq(Seq(Not(a)) -> snap("s3"), Seq(a, b) -> snap("s1"), Seq(a, Not(b)) -> snap("s4")))
    assertMatchesReference(left.merge(right))
    assertMatchesReference(right.merge(left))
  }}

  test("Random recordings agree with the reference construction") { inBothTermModes {
    val random = new Random(20261026)
    val guards = (0 until 5).map(i => guard(s"g$i"))
    val snaps = (0 until 6).map(i => snap(s"s$i"))

    def literal(): Term = {
      val g = guards(random.nextInt(guards.length))
      if (random.nextBoolean()) g else Not(g)
    }

    for (_ <- 0 until 2000) {
      val recordings = Seq.fill(1 + random.nextInt(8)) {
        val path: Stack[Term] = List.fill(random.nextInt(5))(literal())
        path -> snaps(random.nextInt(snaps.length))
      }
      withClue(recordings.toString)(check(recordings))
    }
  }}

  test("Long common guard prefixes agree with the reference construction") { inBothTermModes {
    val prefix = (0 until 200).map(i => guard(s"p$i")).toList
    val (a, b) = (guard("a"), guard("b"))
    check(Seq((prefix :+ a) -> snap("s1"), (prefix :+ Not(a)) -> snap("s2")))
    check(Seq((prefix :+ a) -> snap("s1"), (b :: prefix) -> snap("s2"), (prefix :+ Not(a)) -> snap("s3")))
  }}

  test("Very long common guard prefixes do not overflow the stack") { inBothTermModes {
    val prefix = (0 until 20000).map(i => guard(s"p$i")).toList
    val a = guard("a")
    val recorder = record(Seq((prefix :+ a) -> snap("s1"), (prefix :+ Not(a)) -> snap("s2")))
    assert(recorder.locToSnap((loc, Seq())) == Ite(a, snap("s1"), snap("s2")))
  }}

  test("Many disjoint paths produce a chain of fallbacks") { inBothTermModes {
    val width = 2000
    val guards = (0 until width).map(i => guard(s"g$i"))
    val snaps = (0 until width).map(i => snap(s"s$i"))
    val recordings = guards.zip(snaps).map { case (g, s) => (List(g): Stack[Term]) -> s }
    val recorder = record(recordings)
    val expected = guards.init.zip(snaps.init).foldRight(snaps.last) { case ((g, s), fallback) =>
      Ite(g, Ite(And(Set.empty[Term]), s, fallback), fallback)
    }
    assert(recorder.locToSnap((loc, Seq())) == expected)
    check(recordings.take(300))
  }}
}
