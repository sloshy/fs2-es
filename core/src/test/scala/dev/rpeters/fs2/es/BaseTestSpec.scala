package dev.rpeters.fs2.es

import cats.effect.laws.util.TestContext
import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

abstract class BaseTestSpec extends AnyFreeSpec with Matchers with ScalaCheckPropertyChecks {
  val tc = TestContext()
  implicit val cs = tc.contextShift[IO]
  implicit val timer = tc.timer[IO]
}
