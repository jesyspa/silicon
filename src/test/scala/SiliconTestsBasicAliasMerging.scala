// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package viper.silicon.tests

class SiliconTestsBasicAliasMerging extends SiliconTests {
  override val testDirectories: Seq[String] = Seq("basicAliasMerging")
  override val commandLineArguments: Seq[String] = Seq(
    "--timeout", "60", "--numberOfParallelVerifiers", "1", "--exhaleMode", "0")
}

class SiliconTestsBasicAliasMergingMoreComplete extends SiliconTestsBasicAliasMerging {
  override val commandLineArguments: Seq[String] = Seq(
    "--timeout", "60", "--numberOfParallelVerifiers", "1", "--exhaleMode", "1")
}

class SiliconTestsBasicAliasMergingParallelBranches extends SiliconTestsBasicAliasMerging {
  override val commandLineArguments: Seq[String] = Seq(
    "--timeout", "60", "--numberOfParallelVerifiers", "2", "--exhaleMode", "0", "--parallelizeBranches")
}
