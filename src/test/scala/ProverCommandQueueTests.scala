// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 ETH Zurich.

package viper.silicon.tests

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.verifier.ProverCommandQueue

class ProverCommandQueueTests extends AnyFunSuite {
  test("batches execute asynchronously in submission order") {
    val queue = new ProverCommandQueue
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    var commands = Vector.empty[Int]
    try {
      queue.submit(() => {
        entered.countDown()
        assert(release.await(10, TimeUnit.SECONDS))
        commands :+= 1
        commands :+= 2
      })
      assert(entered.await(10, TimeUnit.SECONDS))
      queue.submit(() => { commands :+= 3 })
      release.countDown()
      queue.await()
      assert(commands == Vector(1, 2, 3))
    } finally {
      release.countDown()
      queue.shutdownNow()
    }
  }

  test("an earlier failure retains its identity through later submissions and awaits until reset") {
    val queue = new ProverCommandQueue
    val failure = new IllegalStateException("prover failed")
    var laterCommandRan = false
    try {
      queue.submit(() => throw failure)
      queue.submit(() => { laterCommandRan = true })
      assert(intercept[IllegalStateException](queue.await()) eq failure)
      queue.submit(() => { laterCommandRan = true })
      assert(intercept[IllegalStateException](queue.await()) eq failure)
      assert(!laterCommandRan)
      queue.reset()
      queue.submit(() => { laterCommandRan = true })
      queue.await()
      assert(laterCommandRan)
    } finally queue.shutdownNow()
  }
}
