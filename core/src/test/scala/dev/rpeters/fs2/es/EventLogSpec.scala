package dev.rpeters.fs2.es

import cats.data.{NonEmptyMap, NonEmptySet}
import cats.effect.IO
import cats.syntax.all._
import fs2.Stream
import org.scalacheck.Prop
import org.scalacheck.effect.PropF
import KeyedContinuousEventLogSource.{KeyedContinuousStreamResult, NonEmptyKeyedContinuousStreamResult}
import fs2.concurrent.Topic
import cats.Functor
import cats.Invariant
import cats.Contravariant
import cats.Show
import syntax._

class EventLogSpec extends BaseTestSpec {

  implicit val stringKeyed = Keyed.instance[String, Int](_.toString)
  implicit val intDriven = Driven.instance[Int, Int] {
    case (i, None)    => Some(i)
    case (i, Some(j)) => Some(i + j)
  }
  implicit val intDrivenNonEmpty = DrivenNonEmpty.instance[Int, Int](_ + _)
  implicit val intKeyedState = KeyedState.instance
  implicit val intKeyedStateNonEmpty = KeyedStateNonEmpty.instance

  test("InMemory event log") {
    def withEl(f: KeyedEventLog[IO, String, Int, Int] => IO[Unit]) =
      KeyedEventLog.inMemory[IO, String, Int]().flatMap(el => el.add(1) >> el.add(2) >> el.add(3) >> f(el))

    val assertOnce = withEl(_.streamOnce.compile.toList.assertEquals(List(1, 2, 3)))
    val assertContinuous = withEl { el =>
      el.streamContinuously
        .take(4)
        .concurrently(Stream.eval(el.add(4)))
        .compile
        .toList
        .assertEquals(List(1, 2, 3, 4))
    }

    val assertState = withEl(_.getState[Int].compile.toList.assertEquals(List(Some(6))))
    val assertStateInitial = withEl(_.getState[Int](1).compile.toList.assertEquals(List(7)))

    val assertOneState = withEl(_.getOneState[Int]("1").compile.toList.assertEquals(List(Some(1))))
    val assertOneStateInitial = withEl(_.getOneState[Int]("1", 1).compile.toList.assertEquals(List(2)))

    val assertOneContinuously =
      withEl { el =>
        el.getOneStateContinuously[Int]("1")
          .take(2)
          .concurrently(Stream.eval(el.add(1)))
          .compile
          .toList
          .assertEquals(List((1, Some(1)), (1, Some(2))))
      }
    val assertOneContinuouslyInitial = withEl { el =>
      el.getOneStateContinuously[Int]("1", 1)
        .take(2)
        .concurrently(Stream.eval(el.add(1)))
        .compile
        .toList
        .assertEquals(List((1, 2), (1, 3)))
    }

    val assertAllKeyed =
      withEl(_.getAllKeyedStates[Int].compile.toList.assertEquals(List(Map("1" -> 1, "2" -> 2, "3" -> 3))))
    val assertAllKeyedInitial =
      withEl(_.getAllKeyedStates(_ => 1).compile.toList.assertEquals(List(Map("1" -> 2, "2" -> 3, "3" -> 4))))

    val assertAllKeyedContinuously = withEl { el =>
      el.getAllKeyedStateContinuously[Int]
        .take(5)
        .concurrently(Stream(1, 2, 3).evalMap(el.add))
        .compile
        .toList
        .assertEquals(
          List(
            KeyedContinuousStreamResult(1, Some(1), Map("1" -> 1)),
            KeyedContinuousStreamResult(2, Some(2), Map("1" -> 1, "2" -> 2)),
            KeyedContinuousStreamResult(3, Some(3), Map("1" -> 1, "2" -> 2, "3" -> 3)),
            KeyedContinuousStreamResult(1, Some(2), Map("1" -> 2, "2" -> 2, "3" -> 3)),
            KeyedContinuousStreamResult(2, Some(4), Map("1" -> 2, "2" -> 4, "3" -> 3))
            // KeyedContinuousStreamResult(3, Some(6), Map("1" -> 2, "2" -> 4, "3" -> 6))
          )
        )
    }
    val assertAllKeyedContinuouslyInitial = withEl { el =>
      el.getAllKeyedStateContinuously[Int](
        NonEmptyMap.of(
          "1" -> 1,
          "2" -> 2,
          "3" -> 3
        )
      ).take(6)
        .concurrently(Stream(1, 2, 3).evalMap(el.add))
        .compile
        .toList
        .assertEquals(
          List(
            NonEmptyKeyedContinuousStreamResult(1, 2, NonEmptyMap.of("1" -> 2, "2" -> 2, "3" -> 3)),
            NonEmptyKeyedContinuousStreamResult(2, 4, NonEmptyMap.of("1" -> 2, "2" -> 4, "3" -> 3)),
            NonEmptyKeyedContinuousStreamResult(3, 6, NonEmptyMap.of("1" -> 2, "2" -> 4, "3" -> 6)),
            NonEmptyKeyedContinuousStreamResult(1, 3, NonEmptyMap.of("1" -> 3, "2" -> 4, "3" -> 6)),
            NonEmptyKeyedContinuousStreamResult(2, 6, NonEmptyMap.of("1" -> 3, "2" -> 6, "3" -> 6)),
            NonEmptyKeyedContinuousStreamResult(3, 9, NonEmptyMap.of("1" -> 3, "2" -> 6, "3" -> 9))
          )
        )
    }

    val assertKeyedStateBatch =
      withEl(_.getKeyedStateBatch[Int](NonEmptySet.of("1")).compile.toList.assertEquals(List(Map("1" -> 1))))
    val assertKeyedStateBatchInitial =
      withEl(
        _.getKeyedStateBatch[Int](NonEmptyMap.of("1" -> 1)).compile.toList.assertEquals(List(NonEmptyMap.of("1" -> 2)))
      )

    val assertKeyedStateBatchContinuously = withEl { el =>
      el.getKeyedStateBatchContinuously[Int](NonEmptySet.of("1"))
        .take(2)
        .concurrently(Stream.eval(el.add(1)))
        .compile
        .toList
        .assertEquals(
          List(
            KeyedContinuousStreamResult(1, Some(1), Map("1" -> 1)),
            KeyedContinuousStreamResult(1, Some(2), Map("1" -> 2))
          )
        )
    }
    val assertKeyedStateBatchContinuouslyInitial = withEl { el =>
      el.getKeyedStateBatchContinuously[Int](NonEmptyMap.of("1" -> 1))
        .take(2)
        .concurrently(Stream.eval(el.add(1)))
        .compile
        .toList
        .assertEquals(
          List(
            NonEmptyKeyedContinuousStreamResult(1, 2, NonEmptyMap.of("1" -> 2)),
            NonEmptyKeyedContinuousStreamResult(1, 3, NonEmptyMap.of("1" -> 3))
          )
        )
    }

    val assertProfunctorRmap = withEl { el =>
      el.rmap(_.toLong)
        .streamOnce
        .compile
        .toList
        .assertEquals(List(1L, 2L, 3L))
    }

    val assertProfunctorLmap = withEl { el =>
      val newEl = el.lmap[Long](_.toInt)
      newEl.add(1L) >> newEl.streamOnce.compile.toList.assertEquals(List(1, 2, 3, 1))
    }

    val assertProfunctorDimap = withEl { el =>
      val newEl = el.dimap[Long, Long](_.toInt)(_.toLong)
      newEl.add(1L) >> newEl.streamOnce.compile.toList.assertEquals(List(1L, 2L, 3L, 1L))
    }

    List(
      assertOnce,
      assertContinuous,
      assertState,
      assertStateInitial,
      assertOneState,
      assertOneStateInitial,
      assertOneContinuously,
      assertOneContinuouslyInitial,
      assertAllKeyed,
      assertAllKeyedInitial,
      assertAllKeyedContinuously,
      assertAllKeyedContinuouslyInitial,
      assertKeyedStateBatch,
      assertKeyedStateBatchInitial,
      assertKeyedStateBatchContinuously,
      assertKeyedStateBatchContinuouslyInitial,
      assertProfunctorRmap,
      assertProfunctorLmap,
      assertProfunctorDimap
    ).reduceLeft(_ >> _)
  }
}
