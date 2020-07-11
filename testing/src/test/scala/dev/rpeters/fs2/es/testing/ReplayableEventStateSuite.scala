package dev.rpeters.fs2.es.testing

import cats.effect.IO
import cats.implicits._
import dev.rpeters.fs2.es.BaseTestSpec

import scala.concurrent.ExecutionContext.Implicits.global
import cats.data.Chain

class ReplayableEventStateSuite extends BaseTestSpec {

  def newEs = ReplayableEventState[IO].initial[Int, Int](0)(_ + _)

  test("increment event count") {
    val program = for {
      es <- newEs
      _ <- es.doNext(1)
      _ <- es.doNext(1)
      _ <- es.doNext(1)
      count <- es.getEventCount
    } yield count

    program.unsafeToFuture().map(c => assertEquals(c, 3))
  }

  test("get events") {
    val program = for {
      es <- newEs
      _ <- es.doNext(1)
      _ <- es.doNext(1)
      _ <- es.doNext(1)
      eventsOne <- es.getEvents
      _ <- es.reset
      eventsTwo <- es.getEvents
    } yield (eventsOne, eventsTwo)

    program.unsafeToFuture().map {
      case (one, two) =>
        assertEquals(one, Chain(1, 1, 1))
        assertEquals(two, Chain.empty)
    }
  }

  test("get index") {
    val program = for {
      es <- newEs
      index1 <- es.getIndex
      _ <- es.doNext(1)
      _ <- es.doNext(1)
      _ <- es.doNext(1)
      index2 <- es.getIndex
      _ <- es.reset
      index3 <- es.getIndex
    } yield (index1, index2, index3)

    program.unsafeToFuture().map {
      case (one, two, three) =>
        assertEquals(one, 0)
        assertEquals(two, 3)
        assertEquals(three, 0)
    }
  }

  test("reset and reset initial") {
    val program = for {
      es <- newEs
      _ <- es.doNext(1)
      _ <- es.doNext(1)
      _ <- es.doNext(1)
      events1 <- es.getEvents
      _ <- es.reset
      oldState <- es.get
      events2 <- es.getEvents
      _ <- es.resetInitial(5)
      events3 <- es.getEvents
      newState <- es.get
    } yield (events1, events2, events3, oldState, newState)

    program.unsafeToFuture().map {
      case (one, two, three, oldState, newState) =>
        assertEquals(one, Chain(1, 1, 1))
        assertEquals(two, Chain.empty)
        assertEquals(three, Chain.empty)
        assertEquals(oldState, 0)
        assertEquals(newState, 5)
    }
  }

  test("seek to") {
    val program = for {
      es <- newEs
      _ <- es.doNext(1)
      index1 <- es.getIndex
      _ <- es.seekTo(0)
      index2 <- es.getIndex
    } yield (index1, index2)

    program.unsafeToFuture().map {
      case (one, two) =>
        assertEquals(one, 1)
        assertEquals(two, 0)
    }
  }
}
