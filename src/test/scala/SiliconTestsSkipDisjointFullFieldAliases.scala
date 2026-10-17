// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.tests

import org.scalatest.ConfigMap

/** Runs the full field alias fixtures with silicon.skipDisjointFullFieldAliases set for this suite. */
class SiliconTestsSkipDisjointFullFieldAliases extends SiliconTests {
  private val property = "silicon.skipDisjointFullFieldAliases"

  override val testDirectories: Seq[String] = Seq("skipDisjointFullFieldAliases")

  override def beforeAll(configMap: ConfigMap): Unit = {
    System.setProperty(property, "true")
    super.beforeAll(configMap)
  }

  override def afterAll(configMap: ConfigMap): Unit = {
    try super.afterAll(configMap)
    finally System.clearProperty(property)
  }
}
