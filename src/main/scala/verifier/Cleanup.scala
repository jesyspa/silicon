// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 ETH Zurich.

package viper.silicon.verifier

/** Runs independent teardown steps even when an earlier step throws an exception. */
private[silicon] object Cleanup {
  def run(steps: (() => Unit)*): Unit = {
    // Cleanup may run on a cancelled verification thread. Defer its interrupt until all
    // steps have been attempted, so blocking cleanup can still release its resources.
    var interrupted = Thread.interrupted()
    var failure: Exception = null

    try {
      steps.foreach { step =>
        try {
          step()
        } catch {
          case e: Exception =>
            if (e.isInstanceOf[InterruptedException]) interrupted = true

            if (failure == null) {
              failure = e
            } else if (failure ne e) {
              // An interruption must not hide a subsequent resource-cleanup failure.
              if (failure.isInstanceOf[InterruptedException] && !e.isInstanceOf[InterruptedException]) {
                e.addSuppressed(failure)
                failure = e
              } else {
                failure.addSuppressed(e)
              }
            }
        } finally {
          // Nested cleanup also restores the flag; defer it again for the next step.
          if (Thread.interrupted()) interrupted = true
        }
      }

      if (failure != null) throw failure
    } finally {
      if (interrupted) Thread.currentThread().interrupt()
    }
  }
}
