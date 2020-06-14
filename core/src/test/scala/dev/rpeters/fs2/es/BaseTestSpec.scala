package dev.rpeters.fs2.es

import cats.effect.laws.util.TestContext
import cats.effect.IO
import munit.ScalaCheckSuite

abstract class BaseTestSpec extends ScalaCheckSuite {
  val tc = TestContext()
  implicit val cs = tc.contextShift[IO]
  implicit val timer = tc.timer[IO]
}
