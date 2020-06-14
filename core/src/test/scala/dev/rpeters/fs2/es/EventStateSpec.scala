package dev.rpeters.fs2.es

import cats.effect._
import fs2.Stream
import org.scalacheck.Prop._

import scala.concurrent.ExecutionContext.Implicits._

class EventStateSpec extends BaseTestSpec {

  val s = "string"
  val strs = List("1", "2", "3")

  test("Hydrated should have the starting value if the hydrated stream never emits") {
    val emptyStream = Stream.empty
    val state = EventState[IO].hydrated[Any, String](s, emptyStream) { (_, str) =>
      List("failed, expected", s"'$str'").mkString(" ")
    }
    state.flatMap(_.get).unsafeToFuture().map(r => assertEquals(r, s))
  }
  test("Hydrated should hydrate state with the given hydrator stream") {
    val eventStream = Stream.emits(strs)
    val state = EventState[IO].hydrated[String, String](s, eventStream) { (e, str) =>
      str + e
    }
    state.flatMap(_.get).unsafeToFuture().map { result =>
      assertEquals(result, strs.foldLeft(s)(_ + _))
    }
  }
  test("Initial should have the starting value") {
    val state = EventState[IO].initial[Any, String](s) { (_, str) =>
      List("failed, expected", s"'$str'").mkString(" ")
    }
    state.flatMap(_.get).unsafeToFuture().map(r => assertEquals(r, s))
  }
}
