package dev.rpeters.fs2.es

import cats.effect.laws.util.TestContext
import cats.effect.{ContextShift, IO, Timer}
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}

abstract class BaseTestSpec extends CatsEffectSuite with ScalaCheckEffectSuite
