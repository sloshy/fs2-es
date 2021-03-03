package dev.rpeters.fs2.es

import cats.data.Chain
import cats.effect._
import cats.effect.kernel.Ref
import cats.syntax.all._
import fs2.Stream
import org.scalacheck.effect.PropF._

import scala.concurrent.duration._

class EventStateCacheSpec extends BaseTestSpec {

  case class Event(key: String, value: Int)

  def getEventLog = EventLog.inMemory[IO, Event]

  def getEventLogWithStreamCounter = Ref[IO].of(0).flatMap { ref =>
    getEventLog.map(_.mapStream(Stream.eval_(ref.update(_ + 1)) ++ _)).tupleRight(ref)
  }

  val baseKey = "baseKey"

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

  test("timed") {
    val (tc, rt) = createDeterministicRuntime

    val testTimed = getEventLogWithStreamCounter.flatMap { case (log, ref) =>
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
          _ <- IO.sleep(3.seconds)
          _ <- use(keys(0))
          _ <- use(keys(1))
          _ <- use(keys(2))
          _ <- use(keys(3))
          _ <- getCount.assertEquals(8, "Waiting for keys to expire reloads state from event log")
        } yield ()
      }
    }

    val fut = testTimed.unsafeToFuture()(rt)

    tc.tick(3.seconds)

    fut
  }

  test("timedBounded") {
    val (tc, rt) = createDeterministicRuntime

    val testTimedBounded = getEventLogWithStreamCounter.flatMap { case (log, ref) =>
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
          _ <- IO.sleep(3.seconds)
          _ <- use(keys(3))
          _ <- use(keys(0))
          _ <- use(keys(2))
          _ <- use(keys(1))
          _ <- getCount.assertEquals(9, "Waiting for keys to expire reloads state from event log")
        } yield ()
      }
    }

    val fut = testTimedBounded.unsafeToFuture()(rt)

    tc.tick(3.seconds)

    fut
  }
}
