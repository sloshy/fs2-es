package dev.rpeters.fs2.es.testing

import cats.data.Chain
import cats.effect.IO
import cats.implicits._
import dev.rpeters.fs2.es.{BaseTestSpec, DrivenNonEmpty}

class ReplayableEventStateSuite extends BaseTestSpec {

  implicit val driven: DrivenNonEmpty[Int, Int] = DrivenNonEmpty.monoid

  def newEs = ReplayableEventState[IO].total[Int, Int](0)

  test("increment event count") {
    val program = for {
      es <- newEs
      _ <- es.doNext(1)
      _ <- es.doNext(1)
      _ <- es.doNext(1)
      count <- es.getEventCount
    } yield count

    program.flatMap(c => IO(assertEquals(c, 3)))
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

    program.map { case (one, two) =>
      IO(assertEquals(one, Chain(1, 1, 1))) >>
        IO(assertEquals(two, Chain.empty))
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

    program.map { case (one, two, three) =>
      IO(assertEquals(one, 0)) >>
        IO(assertEquals(two, 3)) >>
        IO(assertEquals(three, 0))
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

    program.map { case (one, two, three, oldState, newState) =>
      IO(assertEquals(one, Chain(1, 1, 1))) >>
        IO(assertEquals(two, Chain.empty)) >>
        IO(assertEquals(three, Chain.empty)) >>
        IO(assertEquals(oldState, 0)) >>
        IO(assertEquals(newState, 5))
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

    program.map { case (one, two) =>
      IO(assertEquals(one, 1)) >>
        IO(assertEquals(two, 0))
    }
  }
}
