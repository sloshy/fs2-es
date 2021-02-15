package dev.rpeters.fs2.es

import cats.data.NonEmptyMap
import cats.effect.IO
import cats.syntax.all._
import org.scalacheck.effect.PropF._

class EventLogSpec extends BaseTestSpec {

  test("getState") {
    val getLog = EventLog.inMemory[IO, Int]
    implicit val drivenNonEmpty = DrivenNonEmpty.monoid[Int]
    implicit val driven = Driven.monoid[Int]

    forAllF { (i: Int, offset: Int) =>
      getLog.flatMap { log =>
        val addThree = log.add(i) >> log.add(i) >> log.add(i)
        val testGetState = log.getState[Int].compile.lastOrError.assertEquals((i * 3).some)
        val testGetStateInit = log.getState(offset).compile.lastOrError.assertEquals(i * 3 + offset)
        addThree >> testGetState >> testGetStateInit
      }
    }
  }

  test("getOneState and getKeyedState") {
    case class Event(key: String, value: Int)
    val getLog = EventLog.inMemory[IO, Event]
    implicit val keyedNonEmpty = new KeyedStateNonEmpty[String, Event, Int] {
      def handleEvent(a: Int)(e: Event): Int = a + e.value
      def getKey(a: Event): String = a.key
    }
    implicit val keyed = new KeyedState[String, Event, Int] {
      def handleEvent(optA: Option[Int])(e: Event): Option[Int] = optA.map(_ + e.value).getOrElse(e.value).some
      def getKey(a: Event): String = a.key
    }
    forAllF { (key: String, value: Int, initOffset: Int, key2Offset: Int) =>
      val key2 = key + "2"
      val key2Value = value + key2Offset
      getLog.flatMap { log =>
        def add(k: String, offset: Int) = log.add(Event(k, value + offset))
        def addThreeTimes(k: String, offset: Int = 0) = add(k, offset) >> add(k, offset) >> add(k, offset)
        val addFirstThreeTimes = addThreeTimes(key)
        val addSecondThreeTimes = addThreeTimes(key2, key2Offset)
        val setup = addFirstThreeTimes >> addSecondThreeTimes
        val testGetState = log.getOneState[String, Int](key).compile.lastOrError.assertEquals((value * 3).some)
        val testGetStateKey2 = log.getOneState[String, Int](key2).compile.lastOrError.assertEquals((key2Value * 3).some)
        val testGetStateInit =
          log.getOneState(initOffset, key).compile.lastOrError.assertEquals(value * 3 + initOffset)
        val testGetStateKey2Init =
          log.getOneState(initOffset, key2).compile.lastOrError.assertEquals(key2Value * 3 + initOffset)

        val testGetKeyedState = log
          .getKeyedState[String, Int]
          .compile
          .lastOrError
          .assertEquals(Map(key -> (value * 3), key2 -> (key2Value * 3)))

        val testGetKeyedStateInit = log
          .getKeyedState(NonEmptyMap.of(key -> initOffset, key2 -> initOffset))
          .compile
          .lastOrError
          .assertEquals(NonEmptyMap.of(key -> (value * 3 + initOffset), key2 -> (key2Value * 3 + initOffset)))

        setup >> testGetState >> testGetStateKey2 >> testGetStateInit >> testGetStateKey2Init >> testGetKeyedState >> testGetKeyedStateInit
      }
    }

  }

  test("localizeEvent") {
    val getLog = EventLog.inMemory[IO, Long].map(_.localizeEvent[Int](_.toLong))
    forAllF { i: Int =>
      getLog.flatMap { log =>
        log.add(i) >> log.stream.compile.toList.assertEquals(List(i.toLong))
      }
    }
  }

  test("mapStream") {
    val getLog = EventLog.inMemory[IO, Int].map(_.mapStream(_.map(_.toLong)))
    forAllF { i: Int =>
      getLog.flatMap { log =>
        log.add(i) >> log.stream.compile.toList.assertEquals(List(i.toLong))
      }
    }
  }
}
