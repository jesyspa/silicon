package viper.silicon.tests

import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap, Suite}
import org.scalatest.funsuite.AnyFunSuite

class FullFieldPropertyScopeTests extends AnyFunSuite {
  test("restore exact caller property on normal teardown, failed setup and throwing teardown") {
    val key = "silicon.skipDisjointFullFieldAliases"
    val caller = Option(System.getProperty(key))
    def put(value: Option[String]): Unit = value match {
      case Some(s) => System.setProperty(key, s)
      case None => System.clearProperty(key)
    }
    class Parent(failSetup: Boolean, failTeardown: Boolean)
        extends Suite with BeforeAndAfterAllConfigMap {
      override def beforeAll(c: ConfigMap): Unit = {
        assert(System.getProperty(key) == "false")
        if (failSetup) throw new IllegalStateException("setup")
      }
      override def afterAll(c: ConfigMap): Unit = {
        if (failTeardown) throw new IllegalStateException("teardown")
      }
    }
    class Scoped(setup: Boolean, teardown: Boolean)
        extends Parent(setup, teardown) with FullFieldPropertyScope {
      def start(): Unit = beforeAll(ConfigMap("fullFieldAliases" -> "false"))
      def stop(): Unit = afterAll(ConfigMap.empty)
    }
    try {
      for (original <- Seq(None, Some("true"), Some("false"), Some("caller-value"));
           (setup, teardown) <- Seq((false, false), (true, false), (false, true))) {
        put(original)
        val scope = new Scoped(setup, teardown)
        if (setup) intercept[IllegalStateException](scope.start()) else scope.start()
        if (setup) assert(Option(System.getProperty(key)) == original)
        if (teardown) intercept[IllegalStateException](scope.stop()) else scope.stop()
        assert(Option(System.getProperty(key)) == original)
      }
    } finally put(caller)
  }
}
