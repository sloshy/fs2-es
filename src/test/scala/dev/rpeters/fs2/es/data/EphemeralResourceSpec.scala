package dev.rpeters.fs2.es.data

import cats.implicits._
import cats.effect.IO
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.Try
import dev.rpeters.fs2.es.BaseTestSpec

class EphemeralResourceSpec extends BaseTestSpec {

  "EphemeralResource" - {
    "timed" - {
      "should last as long as the specified duration" in {
        val program = for {
          eph <- EphemeralResource[IO].timed(1, 5.seconds)
          _ <- timer.sleep(4.seconds)
          firstTry <- eph.use(_ => IO.unit)
          _ <- timer.sleep(6.seconds)
          secondTry <- eph.use(_ => IO.unit)
          _ <- eph.expired
        } yield (firstTry, secondTry)

        val running = program.unsafeToFuture()

        tc.tick(4.seconds)
        tc.tick(6.seconds)

        val result = Await.result(running, 2.seconds)
        result._1.isDefined shouldBe true
        result._2.isDefined shouldBe false
      }
      "should not expire if the specified duration does not occur between uses" in {
        val program = for {
          eph <- EphemeralResource[IO].timed(1, 5.seconds)
          _ <- eph.expired
        } yield ()

        val running = program.unsafeToFuture()
        tc.tick(5.seconds.minus(1.microsecond))
        Try(Await.ready(running, 1.second)).isFailure shouldBe true
      }
    }
  }

}
