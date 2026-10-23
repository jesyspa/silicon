// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.tests

class SiliconTestsEquivalenceClasses extends SiliconTests {
  override val testDirectories: Seq[String] = Seq("equivalenceClasses")
  override val commandLineArguments: Seq[String] = Seq(
    "--timeout", "30", "--assertTimeout", "5000", "--equivalenceClasses"
  )
}
