package dev.rpeters.fs2.es

import cats.implicits._
import cats.effect._
import cats.effect.implicits._
import cats.effect.concurrent.Deferred
import cats.effect.concurrent.Ref
import cats.Applicative
import io.chrisdavenport.agitation.Agitation
import scala.concurrent.duration.FiniteDuration

sealed trait EphemeralResource[F[_], A] {

  /** Acquires the resource and uses it as long as it still exists. `F[None]` when the resource is expired. */
  def use[B](f: A => F[B]): F[Option[B]]

  /** Asynchronously waits until this resource is no longer available. */
  val expired: F[Unit]
}

object EphemeralResource {
  final class EphemeralResourcePartiallyApplied[F[_]: Concurrent]() {
    def timed[A](a: A, dur: FiniteDuration)(implicit ev: Timer[F]) =
      for {
        ag <- Agitation.timed[F](dur)
        countRef <- Ref[F].of(0)
        isExpired <- Deferred.tryable[F, Unit]
        _ <- (ag.settled >> isExpired.complete(())).start
      } yield new EphemeralResource[F, A] {
        private def agIfZero(i: Int) = if (i == 0) ag.agitate(dur) else Applicative[F].unit
        def use[B](f: A => F[B]): F[Option[B]] = {
          isExpired.tryGet
            .map(_.isDefined)
            .ifM(
              Option.empty[B].pure[F],
              for {
                oldCount <- countRef.modify { i =>
                  (i + 1) -> i
                }
                _ <- agIfZero(oldCount)
                b <- f(a)
                count <- countRef.modify { i =>
                  val next = i - 1
                  next -> next
                }
                _ <- agIfZero(count)
              } yield b.some
            )
        }

        val expired: F[Unit] = ag.settled
      }
    def uses[A](a: A, uses: Int) =
      for {
        countRef <- Ref[F].of(uses)
        isExpired <- Deferred[F, Unit]
      } yield new EphemeralResource[F, A] {
        val expired: F[Unit] = isExpired.get

        def use[B](f: A => F[B]): F[Option[B]] = {
          countRef
            .modify { i =>
              if (i <= 0) {
                0 -> 0
              } else {
                val next = i - 1
                next -> next
              }
            }
            .map(_ == 0)
            .ifM(
              isExpired.complete(()) >> Option.empty[B].pure[F],
              f(a).map(_.some)
            )
        }
      }
  }
  def apply[F[_]: Concurrent] = new EphemeralResourcePartiallyApplied[F]()
}
