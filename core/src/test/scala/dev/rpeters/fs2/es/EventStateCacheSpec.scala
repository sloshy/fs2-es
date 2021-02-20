package dev.rpeters.fs2.es

import cats.data.Chain
import cats.effect._
import cats.effect.concurrent.Ref
import cats.syntax.all._
import fs2.Stream
import org.scalacheck.effect.PropF._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits._
import cats.effect.laws.util.TestContext

class EventStateCacheSpec extends BaseTestSpec {

  case class Event(key: String, value: Int)

  def getEventLog = EventLog.inMemory[IO, Event]

  def getEventLogWithStreamCounter = Ref[IO].of(0).flatMap { ref =>
    getEventLog.map(_.mapStream(Stream.eval_(ref.update(_ + 1)) ++ _)).tupleRight(ref)
  }

  implicit val keyedState = new KeyedState[String, Event, Int] {
    def handleEvent(a: Int)(e: Event): Option[Int] = (a + e.value).some

    def handleEvent(optA: Option[Int])(e: Event): Option[Int] = optA.map(_ + e.value).getOrElse(e.value).some

    def initialize(k: String): Int = 0

    def getKey(a: Event): String = a.key

  }

  def logsAndCaches: IO[List[(EventLog[IO, Event, Event], EventStateCache[IO, String, Event, Int])]] = List(
    getEventLog.flatMap { log => EventStateCache[IO].unbounded[String, Event, Int](log).tupleLeft(log) },
    getEventLog.flatMap { log => EventStateCache[IO].bounded[String, Event, Int](log).tupleLeft(log) },
    getEventLog.flatMap { log => EventStateCache[IO].timed[String, Event, Int](log).tupleLeft(log) },
    getEventLog.flatMap { log => EventStateCache[IO].timedBounded[String, Event, Int](log).tupleLeft(log) }
  ).sequence

  def withLogAndCache(
      f: (EventLog[IO, Event, Event], EventStateCache[IO, String, Event, Int]) => IO[Unit]
  ): IO[Unit] = {
    logsAndCaches.flatMap { list =>
      list.traverse { case (log, cache) => f(log, cache) }.void
    }
  }
  // logAndCache.flatMap { case (log, cache) => f(log, cache) }

  test("use should always return None with an empty event log") {
    forAllF { key: String =>
      withLogAndCache { case (log, cache) =>
        cache
          .use(key)(_.pure[IO])
          .assertEquals(none, "An empty event log should never find state")
      }
    }
  }

  test("use should always return the initialized value of new states") {
    forAllF { (key: String, value: Int) =>
      withLogAndCache { case (log, cache) =>
        log.add(Event(key, value)) >> cache.use(key)(_.pure[IO]).assertEquals(value.some)
      }
    }
  }

  test("useDontCache should have the same results as use") {
    forAllF { (key: String, value: Int) =>
      withLogAndCache { case (log, cache) =>
        val logEvent = log.add(Event(key, value))
        val getSet = for {
          _ <- logEvent >> logEvent >> logEvent
          useRes1 <- cache.use(key)(_.pure[IO])
          useRes2 <- cache.use(key)(_.pure[IO])
          useDontCacheRes1 <- cache.useDontCache(key)(_.pure[IO])
          useDontCacheRes2 <- cache.useDontCache(key)(_.pure[IO])
        } yield List(useRes1, useRes2, useDontCacheRes1, useDontCacheRes2).toSet

        getSet.assertEquals(Set((value * 3).some))
      }
    }
  }

  test("add variants should add events to the underlying event log") {
    forAllF { (key: String, value: Int) =>
      val testAddOnlyCached = withLogAndCache { case (log, cache) =>
        val testInitialLogState = log.stream.compile.toList.assertEquals(Nil, "EventLog should start out empty")
        val testCacheInit =
          cache.addOnlyCached(Event(key, value)).assertEquals(none, "Cache should not apply event to uncached state")
        val testLogAfterInit =
          log.stream.compile.toList.assertEquals(List(Event(key, value)), "EventLog should contain first event")

        val testCacheSecondEvent = cache
          .addOnlyCached(Event(key, value))
          .assertEquals(none, "Cache should not apply event to uncached state")

        val testLogBothEvents = log.stream.compile.toList
          .assertEquals(List(Event(key, value), Event(key, value)), "EventLog should contain both events")

        testInitialLogState >> testCacheInit >> testLogAfterInit >> testCacheSecondEvent >> testLogBothEvents
      }

      val testAddAndCache = withLogAndCache { case (log, cache) =>
        val testInitialLogState = log.stream.compile.toList.assertEquals(Nil, "EventLog should start out empty")
        val testCacheInit =
          cache.addAndCache(Event(key, value)).assertEquals(value.asRight, "Cache should initialize state")
        val testLogAfterInit =
          log.stream.compile.toList.assertEquals(List(Event(key, value)), "EventLog should contain first event")

        val testCacheSecondEvent = cache
          .addAndCache(Event(key, value))
          .assertEquals((value * 2).asRight, "Cache should apply event to in-memory state")

        val testLogBothEvents = log.stream.compile.toList
          .assertEquals(List(Event(key, value), Event(key, value)), "EventLog should contain both events")

        testInitialLogState >> testCacheInit >> testLogAfterInit >> testCacheSecondEvent >> testLogBothEvents
      }

      val testAddQuick = withLogAndCache { case (log, cache) =>
        val testInitialLogState = log.stream.compile.toList.assertEquals(Nil, "EventLog should start out empty")
        val testLogAfterInit =
          cache.addQuick(Event(key, value)) >> log.stream.compile.toList
            .assertEquals(List(Event(key, value)), "EventLog should contain first event")

        val testLogBothEvents = cache
          .addQuick(Event(key, value)) >> log.stream.compile.toList
          .assertEquals(List(Event(key, value), Event(key, value)), "EventLog should contain both events")

        testInitialLogState >> testLogAfterInit >> testLogBothEvents
      }

      testAddOnlyCached >> testAddAndCache >> testAddQuick
    }
  }

  test("should not load state if the existence check returns false") {
    forAllF { (key: String, value: Int) =>
      getEventLog.flatMap { log =>
        EventStateCache[IO].unbounded[String, Event, Int](log, existenceCheck = _ => IO.pure(false)).flatMap { cache =>
          log
            .add(Event(key, value)) >> cache.use(key)(_.pure[IO]).assertEquals(none, "Existence check is not working")
        }
      }
    }
  }
}
