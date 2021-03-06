package dev.rpeters.fs2.es.data

import cats.effect.{ContextShift, IO, Timer}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.laws.util.TestContext
import cats.syntax.all._
import dev.rpeters.fs2.es.BaseTestSpec

import scala.util.Try
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits._
import munit.FunSuite

class ExpiringRefSpec extends FunSuite {

  val tc = TestContext()
  implicit val munitContextShift: ContextShift[IO] = tc.contextShift[IO]
  implicit val munitTimer: Timer[IO] = tc.timer[IO]

  test("should last as long as the specified duration") {
    val program = for {
      eph <- ExpiringRef[IO].timed(1, 5.seconds)
      _ <- Timer[IO].sleep(4.seconds)
      firstTry <- eph.use(_ => IO.unit)
      _ <- Timer[IO].sleep(6.seconds)
      secondTry <- eph.use(_ => IO.unit)
      _ <- eph.expired
    } yield (firstTry, secondTry)

    val running = program.unsafeToFuture()

    tc.tick(4.seconds)
    tc.tick(6.seconds)

    running.map { case (firstTry, secondTry) =>
      assert(firstTry.isDefined)
      assert(!secondTry.isDefined)
    }
  }
  test("should not expire if the specified duration does not occur between uses") {
    val program = for {
      d <- Deferred.tryable[IO, Unit]
      eph <- ExpiringRef[IO].timed(1, 5.seconds)
      _ <- (eph.expired >> d.complete(())).start
      _ <- IO.sleep(5.seconds.minus(1.microsecond))
      completed <- d.tryGet.map(_.isDefined)
    } yield completed

    val running = program.unsafeToFuture()
    tc.tick(5.seconds.minus(1.microsecond))
    running.map(c => assert(!c))
  }

}
