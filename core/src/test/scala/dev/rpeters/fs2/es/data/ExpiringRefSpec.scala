package dev.rpeters.fs2.es.data

import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.kernel.testkit.TestContext
import cats.effect.unsafe.{IORuntime, Scheduler}
import cats.syntax.all._
import dev.rpeters.fs2.es.BaseTestSpec

import scala.concurrent.duration._
import scala.util.Try

class ExpiringRefSpec extends BaseTestSpec {

  test("should last as long as the specified duration") {
    val (tc, rt) = createDeterministicRuntime

    val program = for {
      eph <- ExpiringRef[IO].timed(1, 5.seconds)
      _ <- IO.sleep(4.seconds)
      firstTry <- eph.use(_ => IO.unit)
      _ <- IO.sleep(6.seconds)
      secondTry <- eph.use(_ => IO.unit)
      _ <- eph.expired
    } yield (firstTry, secondTry)

    val running = program.unsafeToFuture()(rt)

    tc.tick(4.seconds)
    tc.tick(6.seconds)

    running.map { case (firstTry, secondTry) =>
      assert(firstTry.isDefined)
      assert(!secondTry.isDefined)
    }(munitExecutionContext)
  }
  test("should not expire if the specified duration does not occur between uses") {
    val (tc, rt) = createDeterministicRuntime

    val program = for {
      d <- Deferred[IO, Unit]
      eph <- ExpiringRef[IO].timed(1, 5.seconds)
      _ <- (eph.expired >> d.complete(())).start
      _ <- IO.sleep(5.seconds.minus(1.microsecond))
      completed <- d.tryGet.map(_.isDefined)
    } yield completed

    val running = program.unsafeToFuture()(rt)
    tc.tick(5.seconds.minus(1.microsecond))
    running.map(c => assert(!c))(munitExecutionContext)
  }

}
