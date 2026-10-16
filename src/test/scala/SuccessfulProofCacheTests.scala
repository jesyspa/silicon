// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.decider

import org.scalatest.BeforeAndAfter
import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Config
import viper.silicon.verifier.Verifier
import viper.silicon.state.Identifier
import viper.silicon.state.terms._

class SuccessfulProofCacheTests extends AnyFunSuite with BeforeAndAfter {
  before { Verifier.config = new Config(Seq()) }

  private lazy val a = Var(Identifier("cache-a"), sorts.Bool, false)
  private lazy val b = Var(Identifier("cache-b"), sorts.Bool, false)
  private lazy val c = Var(Identifier("cache-c"), sorts.Bool, false)

  test("identical successful proofs are reused without running the query") {
    val cache = new SuccessfulProofCache(4)
    assert(cache.prove(a, 0)(true))
    assert(cache.prove(a, 0)(fail("repeated proof")))
    assert(cache.hits == 1)
    assert(cache.queries == 1)
  }

  test("ancestor proofs survive descendants; child proofs cannot escape to siblings") {
    val cache = new SuccessfulProofCache(4)
    assert(cache.prove(a, 0)(true))
    cache.push()
    assert(cache.prove(a, 0)(fail("ancestor proof")))
    assert(cache.prove(b, 0)(true))
    cache.push()
    assert(cache.prove(b, 0)(fail("descendant proof")))
    assert(cache.prove(c, 0)(true))
    cache.pop()
    assert(!cache.prove(c, 0)(false))
    assert(cache.prove(b, 0)(fail("same scope proof")))
    cache.pop()
    cache.push()
    assert(!cache.prove(b, 0)(false))
    assert(cache.prove(a, 0)(fail("parent proof")))
  }

  test("failed and unknown prover results are retried until success") {
    val cache = new SuccessfulProofCache(4)
    // The prover represents both sat and unknown as false.
    assert(!cache.prove(a, 0)(false))
    assert(!cache.prove(a, 0)(false))
    assert(cache.prove(a, 0)(true))
    assert(cache.prove(a, 0)(fail("successful retry")))
    assert(cache.queries == 3)
  }

  test("context replacement clears proofs without losing current scope tracking") {
    val cache = new SuccessfulProofCache(4)
    cache.push()
    assert(cache.prove(a, 0)(true))
    cache.clear()
    assert(!cache.prove(a, 0)(false))
    assert(cache.prove(b, 0)(true))
    cache.pop()
    assert(!cache.prove(b, 0)(false))
  }

  test("reset removes all proofs and statistics") {
    val cache = new SuccessfulProofCache(4)
    cache.push()
    assert(cache.prove(a, 0)(true))
    cache.reset()
    assert(cache.hits == 0 && cache.queries == 0)
    assert(!cache.prove(a, 0)(false))
    assert(cache.prove(b, 0)(true))
    cache.push()
    cache.pop()
    assert(cache.prove(b, 0)(fail("reset base scope")))
  }

  test("option epoch changes invalidate proofs even in a descendant") {
    val cache = new SuccessfulProofCache(4)
    assert(cache.prove(a, 0)(true))
    cache.push()
    assert(!cache.prove(a, 1)(false))
    assert(cache.prove(b, 1)(true))
    cache.pop()
    assert(!cache.prove(b, 1)(false))
    assert(!cache.prove(a, 1)(false))
  }

  test("capacity bounds retained proofs across scopes") {
    val cache = new SuccessfulProofCache(2)
    assert(cache.prove(a, 0)(true))
    cache.push()
    assert(cache.prove(b, 0)(true))
    assert(cache.prove(c, 0)(true))
    assert(cache.prove(b, 0)(fail("retained proof")))
    assert(cache.prove(c, 0)(fail("retained proof")))
    assert(!cache.prove(a, 0)(false))
    cache.pop()
    assert(!cache.prove(b, 0)(false))
  }

  test("disabled cache executes every query for paired measurements") {
    val cache = new SuccessfulProofCache(4, enabled = false)
    assert(cache.prove(a, 0)(true))
    assert(!cache.prove(a, 0)(false))
    assert(cache.hits == 0 && cache.queries == 2)
  }

  test("query exceptions are not cached") {
    val cache = new SuccessfulProofCache(4)
    intercept[IllegalStateException] {
      cache.prove(a, 0)(throw new IllegalStateException("query failed"))
    }
    assert(!cache.prove(a, 0)(false))
  }
}
