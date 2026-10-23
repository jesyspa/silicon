// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.decider

import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Silicon
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.state.Identifier
import viper.silicon.state.terms._
import viper.silver.ast
import viper.silver.reporter.NoopReporter

class PathConditionIndexTests extends AnyFunSuite {
  test("An indexed set resolves residual collisions with equality and retains the first representative") {
    final case class Key(value: Int)
    val a = Key(1)
    val alias = Key(1)
    val b = Key(2)
    val set = InsertionOrderedSet.indexed[Key](_ => 0) + a + b + alias
    assert(set.iterator.toList == List(a, b))
    assert(set.head eq a)
    assert(set.contains(alias) && !set.contains(Key(3)))
    assert((set - alias).iterator.toList == List(b))
    assert(((set - alias) + alias).iterator.toList == List(b, alias))
    assert(set.iterator.toList == List(a, b))
  }

  test("Local membership preserves structural aliases, order and persistent layers under collisions") {
    val silicon = new Silicon(NoopReporter, Nil)
    silicon.parseCommandLine(Seq("dummy.vpr"))
    silicon.start()
    try {
      val base = Var(Identifier("snapshot"), sorts.Snap, false)
      def chain(n: Int): Term = (0 until n).foldLeft(base: Term)((t, _) => new Second(t))
      val terms = (0 until 80).map(n => new First(chain(n)))
      val copies = (0 until 80).map(n => new First(chain(n)))
      assert(terms.zip(copies).forall { case (a, b) => a == b && !(a eq b) })
      assert(terms.map(_.hashCode).distinct.size == 1)
      var expected = InsertionOrderedSet.empty[Term]
      var actual = PathConditionIndex.empty
      val random = new scala.util.Random(1337)
      for (_ <- 0 until 400) {
        val i = random.nextInt(terms.size)
        if (random.nextBoolean()) { expected += terms(i); actual += terms(i) }
        else { expected -= copies(i); actual -= copies(i) }
        assert(actual.iterator.toVector == expected.iterator.toVector)
        assert(actual.hashCode == expected.hashCode)
        assert(actual == expected && expected == actual)
        assert(actual.contains(copies(i)) == expected.contains(copies(i)))
      }
      val saved = actual
      val savedOrder = saved.iterator.toVector
      assert((actual ++ actual.iterator) eq actual)
      actual ++= copies
      assert(saved.iterator.toVector == savedOrder)
      assert(actual.iterator.toVector == (expected ++ copies).iterator.toVector)
      val first = PathConditionIndex.empty + terms.head
      assert((first + copies.head).head eq terms.head)
      assert(((first - copies.head) + copies.head).head eq copies.head)

      // SMT aliases are presentation metadata, not new equality members.
      val resource = ast.Field("resource", ast.Int)()
      val heapSort = sorts.HeapSort(sorts.Int)
      val plain = new SnapToHeap(base, resource, heapSort)
      val named = new SnapToHeap(base, resource, heapSort)
      named.varRepr = Some(App(Fun(Identifier("alias"), Nil, heapSort), Nil))
      assert(plain == named)
      assert((PathConditionIndex.empty + plain).contains(named))

      val stack = new LayeredPathConditionStack
      val conditions = terms.map(t => BuiltinEquals(t, base))
      stack.add(conditions(0))
      val mark = stack.mark()
      stack.add(conditions(1))
      stack.addDefinition(conditions(2))
      val clone = stack.duplicate()
      val recorded = stack.after(mark)
      stack.popUntilMark(mark)
      assert(stack.contains(conditions(0)) && !stack.contains(conditions(1)))
      assert(clone.contains(conditions(1)) && recorded.assumptions.contains(conditions(1)))
      assert(clone.definitionsOnly.assumptions.contains(conditions(2)))
      clone.pushScope()
      clone.add(conditions(3))
      clone.popScope()
      assert(!clone.contains(conditions(3)) && clone.contains(conditions(1)))
    } finally silicon.stop()
  }
}
