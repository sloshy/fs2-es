package dev.rpeters.fs2.es

// import cats.implicits._
import cats.effect._
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import fs2.Stream
import org.scalatest.{FreeSpec, Matchers}
import cats.effect.laws.util.TestContext

class EventStateSpec extends FreeSpec with Matchers with ScalaCheckPropertyChecks {

  "EventState" - {
    "Hydrated" - {
      "Should have the starting value if the hydrated stream never emits" in {
        forAll { s: String =>
          val tc = TestContext()
          implicit val cs = tc.contextShift[IO]
          val emptyStream = Stream.empty
          val state = EventState[IO].hydrated[Any, String](s, emptyStream) { (_, str) =>
            List("failed, expected", s"'$str'").mkString(" ")
          }
          state.flatMap(_.get).unsafeRunSync() shouldBe s
        }
      }
      "Should hydrate state with the given hydrator stream" in {
        forAll { (s: String, strs: List[String]) =>
          val tc = TestContext()
          implicit val cs = tc.contextShift[IO]
          val eventStream = Stream.emits(strs)
          val state = EventState[IO].hydrated[String, String](s, eventStream) { (e, str) =>
            str + e
          }
          state.flatMap(_.get).unsafeRunSync() shouldBe strs.foldLeft(s)(_ + _)
        }
      }
    }
    "Initial" - {
      "Should have the starting value" in {
        forAll { s: String =>
          val tc = TestContext()
          implicit val testCS = tc.contextShift[IO]
          val state = EventState[IO].initial[Any, String](s) { (_, str) =>
            List("failed, expected", s"'$str'").mkString(" ")
          }
          state.flatMap(_.get).unsafeRunSync() shouldBe s
        }
      }
    }
  }
}
