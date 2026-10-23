// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.state.Store
import viper.silicon.state.terms.{False, True}
import viper.silver.ast

class StoreTests extends AnyFunSuite {
  test("Projected lookups preserve absent keys and absent expressions") {
    val x = ast.LocalVar("x", ast.Bool)()
    val y = ast.LocalVar("y", ast.Bool)()
    val absent = ast.LocalVar("absent", ast.Bool)()
    val expression = ast.TrueLit()()
    val store = Store() + (x -> (True, Some(expression))) + (y -> (False, None))

    for (key <- Seq(x, y, absent)) {
      assert(store.get(key) == store.termValues.get(key))
      assert(store.getExp(key) == store.expValues.get(key).flatten)
    }
    assert(store.get(absent).isEmpty)
    assert(store.getExp(absent).isEmpty)
    assert(store.getExp(y).isEmpty)
    assert(store.get(y).contains(False))
    assert(store.getExp(ast.LocalVar("x", ast.Bool)()).contains(expression))
    intercept[NoSuchElementException] { store(absent) }
  }

  test("Lookup projections follow updates and right-biased unions without changing older stores") {
    val x = ast.LocalVar("x", ast.Bool)()
    val y = ast.LocalVar("y", ast.Bool)()
    val expression = ast.TrueLit()()
    val original = Store(x -> (True, Some(expression)))
    val updated = original + (x -> (False, None))
    val combined = original + (Store(y -> (True, None)) + updated)

    assert(original.get(x).contains(True))
    assert(original.getExp(x).contains(expression))
    assert(updated.get(x).contains(False))
    assert(updated.getExp(x).isEmpty)
    assert(combined.get(x).contains(False))
    assert(combined.getExp(x).isEmpty)
    assert(combined.get(y).contains(True))
    assert(combined.values.keys.toSeq == Seq(x, y))
  }
}
