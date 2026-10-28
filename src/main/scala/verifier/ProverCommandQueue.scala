// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 ETH Zurich.

package viper.silicon.verifier

import java.util.concurrent.{Executors, Future}

/** A worker's ordered preamble stream. A failed command poisons the stream until reset or teardown. */
private[silicon] final class ProverCommandQueue {
  private val executor = Executors.newSingleThreadExecutor()
  private var tail: Future[_] = _
  @volatile private var failure: Throwable = _

  // Submission and awaiting belong to the main verifier; execution belongs to this worker alone.
  def submit(command: () => Unit): Unit = {
    tail = executor.submit((() => {
      if (failure == null) {
        try command()
        catch { case e: Throwable => failure = e }
      }
    }): Runnable)
  }

  def await(): Unit = {
    if (tail != null) tail.get()
    if (failure != null) throw failure
  }

  /** Drains the previous program before its prover is reset for reuse. */
  def reset(): Unit = {
    if (tail != null) tail.get()
    tail = null
    failure = null
  }

  def shutdownNow(): Unit = { executor.shutdownNow(); () }
}
