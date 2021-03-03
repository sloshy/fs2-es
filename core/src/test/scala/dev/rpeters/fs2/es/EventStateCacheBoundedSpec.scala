package dev.rpeters.fs2.es

import cats.data.Chain
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all._
import fs2.{Chunk, Stream}
import org.scalacheck.effect.PropF._

import scala.concurrent.duration._

class EventStateCacheBoundedSpec extends BaseTestSpec {

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

  test("bounded") {
    forAllF { (baseKey: String, baseInt: Int) =>
      val testBounded = getEventLog.flatMap { case (ref, log) =>
        EventStateCache[IO].bounded[String, Event, Int](log, maxStates = 3).flatMap { cache =>
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
          } yield ()
        }
      }

      testBounded
    }
  }

  test("unbounded") {
    forAllF { (baseKey: String, baseInt: Int) =>
      val testUnbounded = getEventLog.flatMap { case (ref, log) =>
        EventStateCache[IO].unbounded[String, Event, Int](log).flatMap { cache =>
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
          } yield ()
        }
      }

      testUnbounded
    }
  }

  //For tests of the timed segments, see the special timed spec
}
