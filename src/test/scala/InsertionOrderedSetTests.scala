// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 ETH Zurich.

package viper.silicon.common.collections.immutable

import org.scalatest.funsuite.AnyFunSuite

class InsertionOrderedSetTests extends AnyFunSuite {
  test("bulk updates preserve insertion order through overlapping updates and removals") {
    val original = InsertionOrderedSet(0 until 64)
    val removed = (8 until 60 by 2).toVector
    val remaining = original -- (removed ++ Vector(100, 8)).iterator
    val appended = Vector(8, 64, 10, 63, 8)
    val result = remaining ++ appended.iterator

    assert(remaining.iterator.toVector == (0 until 64).filterNot(removed.contains).toVector)
    assert(result.iterator.toVector == (remaining.iterator.toVector ++ appended).distinct)
    assert(result.contains(8) && !result.contains(12))
    assert(original.iterator.toVector == (0 until 64).toVector)
  }
}
