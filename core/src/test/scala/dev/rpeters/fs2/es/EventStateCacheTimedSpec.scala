package dev.rpeters.fs2.es

import cats.data.Chain
import cats.effect._
import cats.effect.concurrent.Ref
import cats.effect.laws.util.TestContext
import cats.syntax.all._
import fs2.{Chunk, Stream}
import munit.FunSuite

import scala.concurrent.duration._
import munit.Location

class EventStateCacheTimedSpec extends FunSuite {
  val tc = TestContext()
  implicit val cs: ContextShift[IO] = tc.contextShift[IO]
  implicit val timer: Timer[IO] = tc.timer[IO]

  case class Event(key: String, value: Int)

  implicit val keyedState: KeyedState[String, Event, Int] = new KeyedState[String, Event, Int] {
    def handleEvent(optA: Option[Int])(e: Event): Option[Int] = optA.map(_ + e.value).getOrElse(e.value).some
    def getKey(a: Event): String = a.key
  }

  def getEventLog = for {
    inner <- Ref[IO].of(Chain.empty[Event])
    outer <- Ref[IO].of(0)
  } yield (outer -> new EventLog[IO, Event, Event] {
    def add(e: Event) = inner.update(_ :+ e)
    def stream = Stream.eval_(outer.update(_ + 1)) ++ Stream.eval(inner.get).map(Chunk.chain).flatMap(Stream.chunk)
  })

  val baseKey = "baseKey"

  def superAssertEquals[A, B](a: A, b: B, clue: String)(implicit loc: Location, ev: B <:< A) = assertEquals(a, b, clue)

  implicit class ioAssertEquals[A](ioa: IO[A]) {
    def assertEquals[B](thing: B, clue: String)(implicit loc: Location, ev: B <:< A): IO[Unit] =
      ioa.flatMap(a => IO(superAssertEquals(a, thing, clue)))
  }

  test("timed") {
    val testTimed = getEventLog.flatMap { case (ref, log) =>
      EventStateCache[IO].timed[String, Event, Int](log, ttl = 2.seconds).flatMap { cache =>
        val keys = (1 to 4).toList.map(i => s"$baseKey$i")
        val events = (1 to 4).toList.zip(keys).map { case (a, b) => Event(b, a) }
        val addEvents = events.traverse(log.add)

        val getCount = ref.get

        def use(k: String) = cache.use(k)(_ => IO.unit)

        for {
          _ <- addEvents
          _ <- getCount.assertEquals(0, "Initial stream count should be 0")
          _ <- use(keys(0))
          _ <- use(keys(1))
          _ <- use(keys(2))
          _ <- getCount.assertEquals(3, "All new states should be streamed")
          _ <- use(keys(3))
          _ <- getCount.assertEquals(4, "Streaming past max limit kicks least-recently-used out of cache")
          _ <- use(keys(0))
          _ <- use(keys(1))
          _ <- use(keys(2))
          _ <- use(keys(3))
          _ <- getCount.assertEquals(4, "Asking for keys in cache does not stream")
          _ <- timer.sleep(3.seconds)
          _ <- use(keys(0))
          _ <- use(keys(1))
          _ <- use(keys(2))
          _ <- use(keys(3))
          _ <- getCount.assertEquals(8, "Waiting for keys to expire reloads state from event log")
        } yield ()
      }
    }

    val fut = testTimed.unsafeToFuture()

    tc.tick(3.seconds)

    fut
  }

  test("timedBounded") {
    val testTimedBounded = getEventLog.flatMap { case (ref, log) =>
      EventStateCache[IO].timedBounded[String, Event, Int](log, maxStates = 3, ttl = 2.seconds).flatMap { cache =>
        val keys = (1 to 4).toList.map(i => s"$baseKey$i")
        val events = (1 to 4).toList.zip(keys).map { case (a, b) => Event(b, a) }
        val addEvents = events.traverse(log.add)

        val getCount = ref.get

        def use(k: String) = cache.use(k)(_ => IO.unit)

        for {
          _ <- addEvents
          _ <- getCount.assertEquals(0, "Initial stream count should be 0")
          _ <- use(keys(0))
          _ <- use(keys(1))
          _ <- use(keys(2))
          _ <- getCount.assertEquals(3, "All new states should be streamed")
          _ <- use(keys(3))
          _ <- use(keys(0))
          _ <- getCount.assertEquals(5, "Streaming past max limit kicks least-recently-used out of cache")
          _ <- use(keys(3))
          _ <- use(keys(0))
          _ <- getCount.assertEquals(5, "Asking for keys in cache does not stream")
          _ <- timer.sleep(3.seconds)
          _ <- use(keys(3))
          _ <- use(keys(0))
          _ <- use(keys(2))
          _ <- use(keys(1))
          _ <- getCount.assertEquals(9, "Waiting for keys to expire reloads state from event log")
        } yield ()
      }
    }

    val fut = testTimedBounded.unsafeToFuture()

    tc.tick(3.seconds)

    fut
  }
}
