package dev.rpeters.fs2.es

import cats.effect._
import cats.syntax.all._
import fs2.Stream
import org.scalacheck.effect.PropF._
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

import scala.concurrent.ExecutionContext.Implicits._

class EventStateSpec extends BaseTestSpec {
  implicit val drivenNonEmpty = DrivenNonEmpty.monoid[String]

  implicit val driven = Driven.monoid[String]

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
  )(f: EventState[IO, E, Option[A]] => IO[Unit])(implicit driven: Driven[E, A]) = {
    val esList = for {
      es <- EventState[IO].initial[E, A](a)
      signal <- EventState.signalling[IO].initial[E, A](a)
      topic <- EventState.topic[IO].initial[E, A](a)
    } yield List(es, signal, topic)

    esList.flatMap(_.traverse(f))
  }

  def testWithEachTotal[E, A](
      a: A
  )(f: EventState[IO, E, A] => IO[Unit])(implicit driven: DrivenNonEmpty[E, A]) = {
    val esList = for {
      es <- EventState[IO].total[E, A](a)
      signal <- EventState.signalling[IO].total[E, A](a)
      topic <- EventState.topic[IO].total[E, A](a)
    } yield List(es, signal, topic)

    esList.flatMap(_.traverse(f))
  }

  def testWithEachManualEmpty[E, A](ef: (E, Option[A]) => Option[A])(f: EventState[IO, E, Option[A]] => IO[Unit]) = {
    val esList = for {
      es <- EventState[IO].manualEmpty[E, A](ef)
      signal <- EventState.signalling[IO].manualEmpty[E, A](ef)
      topic <- EventState.topic[IO].manualEmpty[E, A](ef)
    } yield List(es, signal, topic)

    esList.flatMap(_.traverse(f))
  }

  def testWithEachManualInitial[E, A](
      a: A
  )(ef: (E, Option[A]) => Option[A])(f: EventState[IO, E, Option[A]] => IO[Unit]) = {
    val esList = for {
      es <- EventState[IO].manualInitial[E, A](a)(ef)
      signal <- EventState.signalling[IO].manualInitial[E, A](a)(ef)
      topic <- EventState.topic[IO].manualInitial[E, A](a)(ef)
    } yield List(es, signal, topic)

    esList.flatMap(_.traverse(f))
  }

  def testWithEachManualTotal[E, A](a: A)(ef: (E, A) => A)(f: EventState[IO, E, A] => IO[Unit]) = {
    val esList = for {
      es <- EventState[IO].manualTotal[E, A](a)(ef)
      signal <- EventState.signalling[IO].manualTotal[E, A](a)(ef)
      topic <- EventState.topic[IO].manualTotal[E, A](a)(ef)
    } yield List(es, signal, topic)

    esList.flatMap(_.traverse(f))
  }

  test("empty, initial, and total should always start with the initial value") {
    forAllF { s: String =>
      val empty = testWithEachEmpty[String, String](es => es.get.assertEquals(None))
      val initial = testWithEachInitial[String, String](s)(es => es.get.assertEquals(Some(s)))
      val total = testWithEachTotal[String, String](s)(es => es.get.assertEquals(s))

      empty >> initial >> total.void
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

      val initial = testWithEachInitial[String, String](s) { es =>
        events.traverse(es.doNext) >> es.get.assertEquals(Some(s + events.mkString))
      }
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

      val total = testWithEachTotal[String, String](s) { es =>
        events.traverse(es.doNext) >> es.get.assertEquals(s + events.mkString)
      }
      val totalStream = testWithEachTotal[String, String](s) { es =>
        es.doNextStream(Stream.emits(events)).compile.drain >> es.get.assertEquals(s + events.mkString)
      }
      val totalStreamToIO = testWithEachTotal[String, String](s) { es =>
        es.doNext(Stream.emits(events)) >> es.get.assertEquals(s + events.mkString)
      }
      val totalHookup = testWithEachTotal[String, String](s) { es =>
        es.hookup(Stream.emits(events)).compile.drain >> es.get.assertEquals(s + events.mkString)
      }
      val totalWithInput = testWithEachTotal[String, String](s) { es =>
        Stream.emits(events).through(es.hookupWithInput).compile.drain >> es.get.assertEquals(s + events.mkString)
      }

      val manualEmpty = testWithEachManualEmpty[String, String]((e, o) => driven.handleEvent(o)(e)) { es =>
        events.traverse(es.doNext).map(_.flattenOption) >> es.get.assertEquals(Some(events.mkString))
      }
      val manualEmptyStream = testWithEachManualEmpty[String, String]((e, o) => driven.handleEvent(o)(e)) { es =>
        es.doNextStream(Stream.emits(events)).compile.drain >> es.get.assertEquals(Some(events.mkString))
      }
      val manualEmptyStreamToIO = testWithEachManualEmpty[String, String]((e, o) => driven.handleEvent(o)(e)) { es =>
        es.doNext(Stream.emits(events)) >> es.get.assertEquals(Some(events.mkString))
      }
      val manualEmptyHookup = testWithEachManualEmpty[String, String]((e, o) => driven.handleEvent(o)(e)) { es =>
        es.hookup(Stream.emits(events)).compile.drain >> es.get.assertEquals(Some(events.mkString))
      }
      val manualEmptyWithInput = testWithEachManualEmpty[String, String]((e, o) => driven.handleEvent(o)(e)) { es =>
        Stream.emits(events).through(es.hookupWithInput).compile.drain >> es.get.assertEquals(Some(events.mkString))
      }

      val manualInitial = testWithEachManualInitial[String, String](s)((e, o) => driven.handleEvent(o)(e)) { es =>
        events.traverse(es.doNext) >> es.get.assertEquals(Some(s + events.mkString))
      }
      val manualInitialStream = testWithEachManualInitial[String, String](s)((e, o) => driven.handleEvent(o)(e)) { es =>
        es.doNextStream(Stream.emits(events)).compile.drain >> es.get.assertEquals(Some(s + events.mkString))
      }
      val manualInitialStreamToIO = testWithEachManualInitial[String, String](s)((e, o) => driven.handleEvent(o)(e)) {
        es =>
          es.doNext(Stream.emits(events)) >> es.get.assertEquals(Some(s + events.mkString))
      }
      val manualInitialHookup = testWithEachManualInitial[String, String](s)((e, o) => driven.handleEvent(o)(e)) { es =>
        es.hookup(Stream.emits(events)).compile.drain >> es.get.assertEquals(Some(s + events.mkString))
      }
      val manualInitialWithInput = testWithEachManualInitial[String, String](s)((e, o) => driven.handleEvent(o)(e)) {
        es =>
          Stream.emits(events).through(es.hookupWithInput).compile.drain >> es.get.assertEquals(
            Some(s + events.mkString)
          )
      }

      val manualTotal = testWithEachManualTotal[String, String](s)((e, a) => drivenNonEmpty.handleEvent(a)(e)) { es =>
        events.traverse(es.doNext) >> es.get.assertEquals(s + events.mkString)
      }
      val manualTotalStream = testWithEachManualTotal[String, String](s)((e, a) => drivenNonEmpty.handleEvent(a)(e)) {
        es =>
          es.doNextStream(Stream.emits(events)).compile.drain >> es.get.assertEquals(s + events.mkString)
      }
      val manualTotalStreamToIO =
        testWithEachManualTotal[String, String](s)((e, a) => drivenNonEmpty.handleEvent(a)(e)) { es =>
          es.doNext(Stream.emits(events)) >> es.get.assertEquals(s + events.mkString)
        }
      val manualTotalHookup = testWithEachManualTotal[String, String](s)((e, a) => drivenNonEmpty.handleEvent(a)(e)) {
        es =>
          es.hookup(Stream.emits(events)).compile.drain >> es.get.assertEquals(s + events.mkString)
      }
      val manualTotalWithInput =
        testWithEachManualTotal[String, String](s)((e, a) => drivenNonEmpty.handleEvent(a)(e)) { es =>
          Stream.emits(events).through(es.hookupWithInput).compile.drain >> es.get.assertEquals(s + events.mkString)
        }

      val testEmpty = empty >> emptyStream >> emptyStreamToIO >> emptyHookup >> emptyWithInput
      val testInitial = initial >> initialStream >> initialStreamToIO >> initialHookup >> initialWithInput
      val testTotal = total >> totalStream >> totalStreamToIO >> totalHookup >> totalWithInput
      val testManualEmpty =
        manualEmpty >> manualEmptyStream >> manualEmptyStreamToIO >> manualEmptyHookup >> manualEmptyWithInput
      val testManualInitial =
        manualInitial >> manualInitialStream >> manualInitialStreamToIO >> manualInitialHookup >> manualInitialWithInput
      val testManualTotal =
        manualTotal >> manualTotalStream >> manualTotalStreamToIO >> manualTotalHookup >> manualTotalWithInput

      testEmpty >> testInitial >> testTotal >> testManualEmpty >> testManualInitial >> testManualTotal.void
    }
  }
}
