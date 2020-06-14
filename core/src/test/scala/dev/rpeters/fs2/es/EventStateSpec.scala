package dev.rpeters.fs2.es

import cats.effect._
import fs2.Stream

class EventStateSpec extends BaseTestSpec {

  "EventState" - {
    "Hydrated" - {
      "should have the starting value if the hydrated stream never emits" in {
        forAll { s: String =>
          val emptyStream = Stream.empty
          val state = EventState[IO].hydrated[Any, String](s, emptyStream) { (_, str) =>
            List("failed, expected", s"'$str'").mkString(" ")
          }
          state.flatMap(_.get).unsafeRunSync() shouldBe s
        }
      }
      "should hydrate state with the given hydrator stream" in {
        forAll { (s: String, strs: List[String]) =>
          val eventStream = Stream.emits(strs)
          val state = EventState[IO].hydrated[String, String](s, eventStream) { (e, str) =>
            str + e
          }
          state.flatMap(_.get).unsafeRunSync() shouldBe strs.foldLeft(s)(_ + _)
        }
      }
    }
    "Initial" - {
      "should have the starting value" in {
        forAll { s: String =>
          val state = EventState[IO].initial[Any, String](s) { (_, str) =>
            List("failed, expected", s"'$str'").mkString(" ")
          }
          state.flatMap(_.get).unsafeRunSync() shouldBe s
        }
      }
    }
  }
}
