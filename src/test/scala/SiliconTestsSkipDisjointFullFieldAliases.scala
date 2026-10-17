// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2026 ETH Zurich.

package viper.silicon.tests

import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap, Suite}
import viper.silver.testing.{AbstractOutput, AnnotatedTestInput, SystemUnderTest}

private[tests] trait FullFieldPropertyScope extends BeforeAndAfterAllConfigMap { this: Suite =>
  private val property = "silicon.skipDisjointFullFieldAliases"
  private var previous: Option[String] = None
  private var active = false
  private def restore(): Unit = if (active) {
    previous match {
      case Some(value) => System.setProperty(property, value)
      case None => System.clearProperty(property)
    }
    active = false
  }
  abstract override def beforeAll(configMap: ConfigMap): Unit = {
    previous = Option(System.getProperty(property))
    active = true
    try {
      System.setProperty(property, configMap.getOrElse("fullFieldAliases", "true").toString)
      super.beforeAll(configMap)
    } catch { case t: Throwable => restore(); throw t }
  }
  abstract override def afterAll(configMap: ConfigMap): Unit = {
    try super.afterAll(configMap)
    finally restore()
  }
}

class SiliconTestsSkipDisjointFullFieldAliases extends SiliconTests with FullFieldPropertyScope {
  override val testDirectories: Seq[String] = Seq("skipDisjointFullFieldAliases")
  override def systemsUnderTest: Seq[SystemUnderTest] = super.systemsUnderTest.map { system =>
    new SystemUnderTest {
      val projectInfo = system.projectInfo
      def run(input: AnnotatedTestInput): Seq[AbstractOutput] = {
        val outputs = system.run(input)
        outputs.foreach(output => println(s"full-field raw: $output"))
        outputs
      }
    }
  }
  override def beforeAll(configMap: ConfigMap): Unit = {
    super.beforeAll(configMap)
    println(s"full-field effective: property=${System.getProperty("silicon.skipDisjointFullFieldAliases")}, " +
      s"moreJoins=${silicon.config.moreJoins()}, exhaleMode=${silicon.config.exhaleMode}")
  }
}
