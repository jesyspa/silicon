// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 ETH Zurich.

package viper.silicon.tests

import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.decider.ProverStdIO
import viper.silver.reporter.QuantifierInstantiationsMessage

class QuantifierProfileTests extends AnyFunSuite {
  test("parse both Z3 quantifier profile formats") {
    val expected = Some(QuantifierInstantiationsMessage("qid", 12, 3, 4))
    assert(ProverStdIO.parseQuantifierProfile("[quantifier_instances] qid : 12 : 3 : 4") == expected)
    assert(ProverStdIO.parseQuantifierProfile("[quantifier_instances] qid : 12 : 5 : 6 : 3 : 4") == expected)
    assert(ProverStdIO.parseQuantifierProfile("[quantifier_instances] qid : 12 : 5") == None)
  }
}
