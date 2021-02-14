package dev.rpeters.fs2.es

import cats.effect._
import cats.syntax.all._
import fs2.Stream
import org.scalacheck.effect.PropF._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

import scala.concurrent.ExecutionContext.Implicits._

class EventStateSpec extends BaseTestSpec {
  implicit val driven = new Driven[String, String] {
    def handleEvent(a: String)(e: String): Option[String] = (a + e).some
    def handleEvent(optA: Option[String])(e: String): Option[String] = optA match {
      case Some(value) => (value + e).some
      case None        => e.some
    }
  }

  val s = "string"
  val strs = List("1", "2", "3")

  def testWithEachEmpty[E, A](f: EventState[IO, E, Option[A]] => IO[Unit])(implicit driven: Driven[E, A]) = {
    val esList = for {
      es <- EventState[IO].empty[E, A]
      signal <- EventState.signalling[IO].empty[E, A]
      topic <- EventState.topic[IO].empty[E, A]
    } yield List(es, signal, topic)

    esList.flatMap(_.traverse(f))
  }

  def testWithEachInitial[E, A](
      a: A
  )(f: EventState[IO, E, Option[A]] => IO[Unit])(implicit driven: DrivenNonEmpty[E, A]) = {
    val esList = for {
      es <- EventState[IO].initial[E, A](a)
      signal <- EventState.signalling[IO].initial[E, A](a)
      topic <- EventState.topic[IO].initial[E, A](a)
    } yield List(es, signal, topic)

    esList.flatMap(_.traverse(f))
  }

  def testWithEachInitialDefault[E, A](
      a: A
  )(f: EventState[IO, E, A] => IO[Unit])(implicit driven: DrivenNonEmpty[E, A]) = {
    val esList = for {
      es <- EventState[IO].initialDefault[E, A](a)
      signal <- EventState.signalling[IO].initialDefault[E, A](a)
      topic <- EventState.topic[IO].initialDefault[E, A](a)
    } yield List(es, signal, topic)

    esList.flatMap(_.traverse(f))
  }

  test("empty, initial, and initialDefault should always start with the default value") {
    forAllF { s: String =>
      val empty = testWithEachEmpty[String, String](es => es.get.assertEquals(None))
      val initial = testWithEachInitial[String, String](s)(es => es.get.assertEquals(Some(s)))
      val default = testWithEachInitialDefault[String, String](s)(es => es.get.assertEquals(s))

      empty >> initial >> default.void
    }
  }

  test("doNext, doNextStream, hookup, and hookupWithInput should apply events to state") {
    forAllF(arbitrary[String], Gen.nonEmptyListOf(arbitrary[String])) { (s: String, events: List[String]) =>
      val empty = testWithEachEmpty[String, String] { es =>
        events.traverse(es.doNext).map(_.flattenOption) >> es.get.assertEquals(Some(events.mkString))
      }
      val emptyStream = testWithEachEmpty[String, String] { es =>
        es.doNextStream(Stream.emits(events)).compile.drain >> es.get.assertEquals(Some(events.mkString))
      }
      val emptyStreamToIO = testWithEachEmpty[String, String] { es =>
        es.doNext(Stream.emits(events)) >> es.get.assertEquals(Some(events.mkString))
      }
      val emptyHookup = testWithEachEmpty[String, String] { es =>
        es.hookup(Stream.emits(events)).compile.drain >> es.get.assertEquals(Some(events.mkString))
      }
      val emptyWithInput = testWithEachEmpty[String, String] { es =>
        Stream.emits(events).through(es.hookupWithInput).compile.drain >> es.get.assertEquals(Some(events.mkString))
      }
      val initial = testWithEachInitial[String, String](s)(es =>
        events.traverse(es.doNext) >> es.get.assertEquals(Some(s + events.mkString))
      )
      val initialStream = testWithEachInitial[String, String](s) { es =>
        es.doNextStream(Stream.emits(events)).compile.drain >> es.get.assertEquals(Some(s + events.mkString))
      }
      val initialStreamToIO = testWithEachInitial[String, String](s) { es =>
        es.doNext(Stream.emits(events)) >> es.get.assertEquals(Some(s + events.mkString))
      }
      val initialHookup = testWithEachInitial[String, String](s) { es =>
        es.hookup(Stream.emits(events)).compile.drain >> es.get.assertEquals(Some(s + events.mkString))
      }
      val initialWithInput = testWithEachInitial[String, String](s) { es =>
        Stream.emits(events).through(es.hookupWithInput).compile.drain >> es.get.assertEquals(Some(s + events.mkString))
      }
      val default = testWithEachInitialDefault[String, String](s)(es =>
        events.traverse(es.doNext) >> es.get.assertEquals(s + events.mkString)
      )
      val defaultStream = testWithEachInitialDefault[String, String](s) { es =>
        es.doNextStream(Stream.emits(events)).compile.drain >> es.get.assertEquals(s + events.mkString)
      }
      val defaultStreamToIO = testWithEachInitialDefault[String, String](s) { es =>
        es.doNext(Stream.emits(events)) >> es.get.assertEquals(s + events.mkString)
      }
      val defaultHookup = testWithEachInitialDefault[String, String](s) { es =>
        es.hookup(Stream.emits(events)).compile.drain >> es.get.assertEquals(s + events.mkString)
      }
      val defaultWithInput = testWithEachInitialDefault[String, String](s) { es =>
        Stream.emits(events).through(es.hookupWithInput).compile.drain >> es.get.assertEquals(s + events.mkString)
      }

      val testEmpty = empty >> emptyStream >> emptyStreamToIO >> emptyHookup >> emptyWithInput
      val testInitial = initial >> initialStream >> initialStreamToIO >> initialHookup >> initialWithInput
      val testDefault = default >> defaultStream >> defaultStreamToIO >> defaultHookup >> defaultWithInput

      testEmpty >> testInitial >> testDefault.void
    }
  }
}
