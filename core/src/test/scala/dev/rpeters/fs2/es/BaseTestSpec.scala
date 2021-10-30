package dev.rpeters.fs2.es

import cats.effect.kernel.testkit.TestContext
import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

abstract class BaseTestSpec extends CatsEffectSuite with ScalaCheckEffectSuite {
  // TODO: Figure out TestContext
  // val tc = TestContext()
  override def munitExecutionContext: ExecutionContext = ExecutionContext.global

  // Copied from FS2
  protected def createDeterministicRuntime: (TestContext, IORuntime) = {
    val ctx = TestContext()

    val scheduler = new Scheduler {
      def sleep(delay: FiniteDuration, action: Runnable): Runnable = {
        val cancel = ctx.schedule(delay, action)
        new Runnable { def run() = cancel() }
      }

      def nowMillis() = ctx.now().toMillis
      def monotonicNanos() = ctx.now().toNanos
    }

    val runtime = IORuntime(ctx, ctx, scheduler, () => (), IORuntimeConfig())

    (ctx, runtime)
  }
}
