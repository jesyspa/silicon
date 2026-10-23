// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.decider

import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.{Config, Silicon}
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.state.Identifier
import viper.silicon.state.terms._
import viper.silicon.verifier.Verifier
import viper.silver.ast
import viper.silver.reporter.NoopReporter

class PathConditionIndexTests extends AnyFunSuite {
  test("Indexed factories safely widen without retaining an element-typed callback") {
    val strings = InsertionOrderedSet.indexed[String](_.##) + "a"
    val wide = InsertionOrderedSet.from[Any](strings)
    assert(wide eq strings)
    assert(!wide.contains(1))
    assert((wide + 1).iterator.toList == List("a", 1))
    assert((wide - 1) eq wide)
    assert(strings.iterator.toList == List("a"))
  }

  test("Indexed numeric sets preserve symmetry, transitivity and public hash contracts") {
    val ints = InsertionOrderedSet.indexed[Int](_.##) + 1
    val longs = InsertionOrderedSet.indexed[Long](_.##) + 1L
    assert(ints == longs && longs == ints)
    assert(Set[Any](1L) == ints && ints == Set[Any](1L))
    assert(Set[Any](1) == longs && longs == Set[Any](1))
    assert(ints.hashCode == longs.hashCode && longs.hashCode == Set[Any](1.0d).hashCode)
    for (values <- Seq(Seq[Any](1, 1L, 1.0f, 1.0d),
                       Seq[Any](4294967297L, 4294967297d, BigInt("4294967297")))) {
      val sets = values.flatMap { value =>
        Seq(InsertionOrderedSet.indexed[Any](_.##) + value,
            InsertionOrderedSet(value), Set(value),
            scala.collection.immutable.HashSet(value))
      }
      for (a <- sets; b <- sets; c <- sets) {
        assert(a == b && b == a)
        assert(b == c && a == c)
        assert(a.hashCode == b.hashCode)
      }
      val retained = values.foldLeft(InsertionOrderedSet.indexed[Any](_.##))(_ + _)
      assert(retained.size == 1 && retained.head == values.head)
      for (value <- values) {
        assert(retained.contains(value))
        assert((retained - value).isEmpty)
      }
    }
    // Double.hashCode differs from universal numeric hashing even for 1.0.
    assert(1.0d.hashCode != 1.##)
    assert((InsertionOrderedSet.from[Any](longs) + 1.0d).size == 1)
    assert(InsertionOrderedSet.from[Any](longs).contains(1.0d))
  }

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
      PathConditionWideningChecks.check(base)
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

private[decider] object PathConditionWideningChecks {
  def check(term: Term): Unit = {
    val narrow = PathConditionIndex.empty + term
    val wide = InsertionOrderedSet.from[Any](narrow)
    assert(wide eq narrow)
    assert(!wide.contains("foreign") && !wide.contains(1L) && !wide.contains(null))
    assert((wide - "foreign") eq wide)
    val mixed = wide ++ List("foreign", 1L, 1.0d, null)
    val ordinary = InsertionOrderedSet.from[Any](List(term, "foreign", 1L, 1.0d, null))
    assert(mixed.iterator.toList == ordinary.iterator.toList)
    assert(mixed.head.asInstanceOf[AnyRef] eq term)
    assert(mixed == ordinary && ordinary == mixed)
    assert(mixed == ordinary.toSet && ordinary.toSet == mixed)
    assert(mixed.hashCode == ordinary.hashCode && mixed.hashCode == ordinary.toSet.hashCode)
    assert(mixed.contains(1) && mixed.contains(1.0f))
    assert((mixed - 1.0d).iterator.toList == (ordinary - 1).iterator.toList)
    assert((mixed - term).iterator.toList == List("foreign", 1L, null))
    assert((mixed - null).iterator.toList == List(term, "foreign", 1L))
    assert(narrow.size == 1 && (narrow.head eq term))
  }
}

// Run separately from the structural suite: flyweight configuration is JVM-global.
class FlyweightPathConditionIndexTests extends AnyFunSuite {
  test("Actual flyweight Term sets support factory widening without changing identity semantics") {
    Verifier.config = new Config(Seq("--prover", "Z3-API", "dummy.vpr"))
    assert(Verifier.config.useFlyweight)
    val base = Var(Identifier("flyweight-snapshot"), sorts.Snap, false)
    val term = First(base)
    assert(term eq First(base))
    assert((PathConditionIndex.empty + term).contains(First(base)))
    assert(!(PathConditionIndex.empty + term).contains(new First(base)))
    PathConditionWideningChecks.check(term)
  }
}
