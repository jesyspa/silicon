// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.common.collections.immutable

import scala.collection.immutable.{AbstractSet, SetOps, StrictOptimizedSetOps, VectorMap}
import scala.collection.{IterableFactory, IterableFactoryDefaults, IterableOnce, mutable}

/** An immutable set with insertion-order iteration and hash-indexed membership.
  * Re-inserting an element retains its position and its original representative.
  * Membership performance depends on the distribution of the index hash.
  */
final class InsertionOrderedSet[E] private (
    private val elements: VectorMap[Any, Unit],
    private val indexHash: E => Int = null)
    extends AbstractSet[E]
       with SetOps[E, InsertionOrderedSet, InsertionOrderedSet[E]]
       with StrictOptimizedSetOps[E, InsertionOrderedSet, InsertionOrderedSet[E]]
       with IterableFactoryDefaults[E, InsertionOrderedSet] {

  override def iterableFactory: IterableFactory[InsertionOrderedSet] = InsertionOrderedSet

  private def key(elem: E): Any =
    if (indexHash == null) elem else new InsertionOrderedSet.IndexKey(elem, indexHash(elem))

  def contains(elem: E): Boolean = elements.contains(key(elem))

  def incl(elem: E): InsertionOrderedSet[E] = {
    val k = key(elem)
    if (elements.contains(k)) this
    else new InsertionOrderedSet(elements.updated(k, ()), indexHash)
  }

  def excl(elem: E): InsertionOrderedSet[E] = {
    val updated = elements.removed(key(elem))
    if (updated eq elements) this
    else new InsertionOrderedSet(updated, indexHash)
  }

  override def concat(that: IterableOnce[E]): InsertionOrderedSet[E] =
    that.iterator.foldLeft(this)((set, elem) => set.incl(elem))

  def iterator: Iterator[E] =
    if (indexHash == null) elements.keysIterator.asInstanceOf[Iterator[E]]
    else elements.keysIterator.map(_.asInstanceOf[InsertionOrderedSet.IndexKey[E]].value)

  override def size: Int = elements.size
  override def knownSize: Int = elements.size
  override def isEmpty: Boolean = elements.isEmpty

  override protected[this] def className: String = "InsertionOrderedSet"
}

object InsertionOrderedSet extends IterableFactory[InsertionOrderedSet] {
  private final class IndexKey[E](val value: E, hash: Int) {
    override def hashCode(): Int = hash
    override def equals(other: Any): Boolean = other match {
      case key: IndexKey[_] => value == key.value
      case _ => false
    }
  }

  /** The index hash must be stable and equal for equal elements. Equality and iteration
    * still use the original elements; the hash is private to this set's membership index.
    */
  def indexed[E](hash: E => Int): InsertionOrderedSet[E] =
    new InsertionOrderedSet[E](VectorMap.empty, hash)

  private val emptySet = new InsertionOrderedSet[Any](VectorMap.empty)

  def empty[E]: InsertionOrderedSet[E] = emptySet.asInstanceOf[InsertionOrderedSet[E]]

  def from[E](source: IterableOnce[E]): InsertionOrderedSet[E] = source match {
    case set: InsertionOrderedSet[E @unchecked] => set
    case _ => (newBuilder[E] ++= source).result()
  }

  def newBuilder[E]: mutable.Builder[E, InsertionOrderedSet[E]] =
    new mutable.Builder[E, InsertionOrderedSet[E]] {
      private var elements = VectorMap.empty[Any, Unit]

      def addOne(elem: E): this.type = {
        if (!elements.contains(elem)) elements = elements.updated(elem, ())
        this
      }

      def clear(): Unit = elements = VectorMap.empty[Any, Unit]

      def result(): InsertionOrderedSet[E] =
        if (elements.isEmpty) empty else new InsertionOrderedSet(elements)
    }

  def apply[E](): InsertionOrderedSet[E] = empty
  def apply[E](e: E): InsertionOrderedSet[E] = empty[E] + e
  def apply[E](es: InsertionOrderedSet[E]): InsertionOrderedSet[E] = es
  def apply[E](es: Iterable[E]): InsertionOrderedSet[E] = from(es)
}
