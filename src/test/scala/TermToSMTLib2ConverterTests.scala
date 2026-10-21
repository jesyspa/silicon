// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

import org.scalatest.funsuite.AnyFunSuite
import viper.silicon.Silicon
import viper.silicon.decider.TermToSMTLib2Converter
import viper.silicon.state.Identifier
import viper.silicon.state.terms._
import viper.silver.reporter.NoopReporter

class TermToSMTLib2ConverterTests extends AnyFunSuite {
  /* Term equality depends on the verifier configuration */
  val silicon = new Silicon(NoopReporter, Nil)
  silicon.parseCommandLine(Seq("dummy.vpr"))
  silicon.start()

  val termConverter = new TermToSMTLib2Converter()
  termConverter.start()

  val p = Var(Identifier("p"), sorts.Perm, false)
  val q = Var(Identifier("q"), sorts.Perm, false)

  /* p, p - min(p, q), ... : the tree doubles at each level */
  def nested(base: Term, depth: Int): Term =
    (1 to depth).foldLeft(base)((t, _) => PermMinus(t, PermMin(t, q)))

  def occurrences(s: String, sub: String): Int = s.sliding(sub.length).count(_ == sub)

  test("Small terms are rendered without lets") {
    assert(termConverter.convert(nested(p, 2)) ==
      "(- (- p ($Perm.min p q)) ($Perm.min (- p ($Perm.min p q)) q))")
  }

  test("Repeated subterms of large terms are rendered once") {
    val rendered = termConverter.convert(nested(p, 30))
    assert(rendered.startsWith("(let ("))
    assert(occurrences(rendered, "$Perm.min") == 30)
  }

  test("Subterms with bound variables are not shared across their binder") {
    val big = nested(p, 12)
    val body = PermLess(nested(Var(Identifier("x"), sorts.Perm, false), 12), big)
    val rendered = termConverter.convert(And(PermLess(NoPerm, big), Quantification(Forall, Seq(Var(Identifier("x"), sorts.Perm, false)), body, Nil)))
    val quantifier = rendered.substring(rendered.indexOf("(forall"))
    assert(rendered.startsWith("(let ("))
    assert(!quantifier.contains("$t!"))
    assert(occurrences(quantifier, "$Perm.min") == (1 << 13) - 2)
  }
}
