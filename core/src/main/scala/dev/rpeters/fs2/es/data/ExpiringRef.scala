package dev.rpeters.fs2.es.data

import cats.implicits._
import cats.effect.implicits._
import cats.effect.kernel.{Deferred, Ref, Temporal}
import cats.Applicative
import io.chrisdavenport.agitation.Agitation

import scala.concurrent.duration.{Duration, FiniteDuration}
import cats.effect.Concurrent

sealed trait ExpiringRef[F[_], A] {

  /** Tries to acquire the reference and uses it as long as it still exists. `F[None]` when expired. */
  def use[B](f: A => F[B]): F[Option[B]]

  /** Asynchronously waits until this reference is no longer available. */
  val expired: F[Unit]
}

object ExpiringRef {
  final class ExpiringRefPartiallyApplied[F[_]: Concurrent]() {

    /** Construct an `ExpiringRef` that expires references after `dur` time between uses. */
    def timed[A](a: A, dur: FiniteDuration)(implicit ev: Temporal[F]) =
      for {
        ag <- Agitation.timed[F](dur)
        countRef <- Ref[F].of(0)
        isExpired <- Deferred[F, Unit]
        _ <- (ag.settled >> isExpired.complete(())).start
      } yield new ExpiringRef[F, A] {
        def use[B](f: A => F[B]): F[Option[B]] = {
          isExpired.tryGet
            .map(_.isDefined)
            .ifM(
              Option.empty[B].pure[F],
              for {
                oldCount <- countRef.modify { i =>
                  (i + 1) -> i
                }
                _ <- if (oldCount == 0) ag.agitate(Duration.Inf) else Applicative[F].unit
                b <- f(a)
                newCount <- countRef.modify { i =>
                  val next = i - 1
                  next -> next
                }
                _ <- if (newCount == 0) ag.agitate(dur) else Applicative[F].unit
              } yield b.some
            )
        }

        val expired: F[Unit] = ag.settled
      }

    /** Construct an `ExpiringRef` that expires references after `uses` number of uses. */
    def uses[A](a: A, uses: Int) =
      for {
        countRef <- Ref[F].of(uses)
        isExpired <- Deferred[F, Unit]
      } yield new ExpiringRef[F, A] {
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
              isExpired.complete(()).attempt >> Option.empty[B].pure[F],
              f(a).map(_.some)
            )
        }
      }
  }

  def apply[F[_]: Temporal] = new ExpiringRefPartiallyApplied[F]()
}
