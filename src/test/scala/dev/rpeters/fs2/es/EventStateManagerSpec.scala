package dev.rpeters.fs2.es

// import cats.implicits._
import cats.effect._
import fs2.Stream
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scala.concurrent.duration._
import cats.effect.laws.util.TestContext
import scala.concurrent.Await
import org.scalatest.FreeSpec
import org.scalatest.Matchers

class EventStateManagerSpec extends FreeSpec with Matchers with ScalaCheckPropertyChecks {
  "EventStateManager" - {
    "Rehydrating" - {
      "Should reload state after the TTL elapses" in {
        val tc = TestContext()
        implicit val cs = tc.contextShift[IO]
        implicit val timer = tc.timer[IO]

        val manager =
          EventStateManager[IO].rehydrating[String, Int, Int](_ => 0)(_ => Stream(1, 2, 3))(_ + _)(5.seconds)
        val program = manager.flatMap { m =>
          for {
            first <- m.use("test")(_.doNext(1))
            _ <- timer.sleep(2.seconds)
            second <- m.use("test")(_.get)
            _ <- timer.sleep(6.seconds)
            third <- m.use("test")(_.get)
          } yield (first, second, third)
        }
        val running = program.unsafeToFuture()
        tc.tick(2.seconds)
        tc.tick(10.seconds)
        val result = Await.result(running, 2.seconds)
        val expected = (Some(7), Some(7), Some(6))
        result shouldBe expected
      }
    }
    "Should not add state that already exists in-memory" in {
      val tc = TestContext()
      implicit val cs = tc.contextShift[IO]
      implicit val timer = tc.timer[IO]

      // Manager is configured such that the existence check is always false, forcing it to rely on a memory check.
      val manager = EventStateManager[IO].rehydrating[String, Int, Int](_ => 1)(_ => Stream(1, 2, 3))(_ + _)(
        5.seconds,
        _ => IO.pure(false)
      )
      val program = manager.flatMap { m =>
        for {
          added <- m.add("test")
          notAdded <- m.add("test")
        } yield (added, notAdded)
      }

      val running = program.unsafeToFuture()
      val (added, notAdded) = Await.result(running, 2.seconds)
      added shouldBe true
      notAdded shouldBe false
    }
    "Should not add state that already exists in the event log" in {
      val tc = TestContext()
      implicit val cs = tc.contextShift[IO]
      implicit val timer = tc.timer[IO]

      // Manager is configured to have an existence check that always returns true.
      val manager = EventStateManager[IO].rehydrating[String, Int, Int](_ => 1)(_ => Stream.empty)(_ + _)(
        5.seconds,
        k => if (k == "test") IO.pure(false) else IO.pure(true)
      )
      val program = manager.flatMap { m =>
        for {
          added <- m.add("test")
          notAdded <- m.add("bad-test")
        } yield (added, notAdded)
      }

      val running = program.unsafeToFuture()
      val (added, notAdded) = Await.result(running, 2.seconds)
      added shouldBe true
      notAdded shouldBe false
    }
  }
}
