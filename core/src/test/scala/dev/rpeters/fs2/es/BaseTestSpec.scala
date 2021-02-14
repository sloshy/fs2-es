package dev.rpeters.fs2.es

import cats.effect.laws.util.TestContext
import cats.effect.{ContextShift, IO, Timer}
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import scala.concurrent.ExecutionContext

abstract class BaseTestSpec extends CatsEffectSuite with ScalaCheckEffectSuite {
  //TODO: Figure out TestContext
  // val tc = TestContext()
  override def munitExecutionContext: ExecutionContext = ExecutionContext.global
}
