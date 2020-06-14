package dev.rpeters.fs2.es

import cats.data.Chain
import cats.effect._
import cats.effect.concurrent.Ref
import fs2.Stream
import scala.concurrent.duration._

import scala.concurrent.ExecutionContext.Implicits._

class EventStateCacheSpec extends BaseTestSpec {
  test("should reload state after the TTL elapses") {
    val cache =
      EventStateCache[IO].rehydrating[String, Int, Int](_ => 0)(_ => Stream(1, 2, 3))(_ + _)(5.seconds)
    val program = cache.flatMap { c =>
      for {
        first <- c.use("test")(_.doNext(1))
        _ <- timer.sleep(2.seconds)
        second <- c.use("test")(_.get)
        _ <- timer.sleep(6.seconds)
        third <- c.use("test")(_.get)
      } yield (first, second, third)
    }
    val running = program.unsafeToFuture()
    tc.tick(2.seconds)
    tc.tick(10.seconds)
    val expected = (Some(7), Some(7), Some(6))
    running.map(result => assertEquals(result, expected))
  }
  test("should not add state that already exists in-memory") {
    // Cache is configured such that the existence check is always false, forcing it to rely on a memory check.
    val cache = EventStateCache[IO].rehydrating[String, Int, Int](_ => 1)(_ => Stream(1, 2, 3))(_ + _)(
      5.seconds,
      _ => IO.pure(false)
    )
    val program = cache.flatMap { c =>
      for {
        added <- c.add("test")
        notAdded <- c.add("test")
      } yield (added, notAdded)
    }

    val running = program.unsafeToFuture()
    running.map {
      case (added, notAdded) =>
        assert(added)
        assert(!notAdded)
    }
  }
  test("should not add state that already exists in the event log") {
    val cache = EventStateCache[IO].rehydrating[String, Int, Int](_ => 1)(_ => Stream.empty)(_ + _)(
      5.seconds,
      k => if (k == "test") IO.pure(false) else IO.pure(true)
    )
    val program = cache.flatMap { c =>
      for {
        added <- c.add("test")
        notAdded <- c.add("bad-test")
      } yield (added, notAdded)
    }

    val running = program.unsafeToFuture()
    running.map {
      case (added, notAdded) =>
        assert(added)
        assert(!notAdded)
    }
  }
  test("should allow using state that has been added") {
    val cache =
      EventStateCache[IO].rehydrating[String, Int, Int](_ => 1)(_ => Stream.empty)(_ + _)(
        5.seconds,
        _ => IO.pure(false)
      )
    val program = cache.flatMap { c =>
      for {
        firstAttempt <- c.use("test")(_.get)
        added <- c.add("test")
        secondAttempt <- c.use("test")(_.get)
      } yield (firstAttempt, added, secondAttempt)
    }

    val (firstAttempt, added, secondAttempt) = program.unsafeRunSync()
    program.unsafeToFuture().map {
      case (firstAttempt, added, secondAttempt) =>
        assertEquals(firstAttempt, None)
        assert(added)
        assertEquals(secondAttempt, Some(1))
    }
  }
  test("should rehydrate state that has been manually added to the event stream") {
    val inMemoryPersistence = Ref.of[IO, Chain[Int]](Chain.empty)
    val program = for {
      ref <- inMemoryPersistence
      c <- EventStateCache[IO].rehydrating[String, Int, Int](_ => 1)(_ =>
        Stream.eval(ref.get).flatMap(x => Stream.emits(x.toList)).take(1)
      )(_ + _)(5.seconds)
      added <- c.add("test")
      _ <- ref.update(_ :+ 0) //Add a no-op event
      _ <- IO.sleep(6.seconds)
      result <- c.use("test")(_.get)
    } yield result

    val running = program.unsafeToFuture()
    tc.tick(6.seconds)
    running.map { result => assertEquals(result, Some(1)) }
  }
}
