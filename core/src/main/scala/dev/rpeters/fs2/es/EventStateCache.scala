package dev.rpeters.fs2.es

import cats.implicits._
import cats.effect._
import fs2.{Pipe, Stream}
import scala.concurrent.duration.FiniteDuration
import cats.effect.concurrent.Ref

import data._

/** Caches EventState values by key, allowing you to use event-sourced state repeatedly. */
sealed trait EventStateCache[F[_], K, E, A] {

  /** Access some event state by key if it exists. */
  def use[B](k: K)(f: EventState[F, E, A] => F[B]): F[Option[B]]

  /** Add a new state to the manager by key. */
  def add(k: K): F[Boolean]

  /** Forwards events to their `EventState` by key.
    * Emits key/new state pairs (`None` if state does not exist)
    */
  val hookup: Pipe[F, (K, E), (K, Option[A])]

  /** Forwards events to a specific `EventState` by key. See `hookup`. */
  def hookupKey(k: K): Pipe[F, E, (K, Option[A])] = _.map(e => k -> e).through(hookup)
}

object EventStateCache {

  final class EventStateCachePartiallyApplied[F[_]: Sync, G[_]: Concurrent]() {

    /** Rehydrates entities by key as-needed. */
    def rehydrating[K, E, A](initializer: K => A)(keyHydrator: K => Stream[G, E])(eventProcessor: (E, A) => A)(
        dur: FiniteDuration,
        existenceCheck: K => G[Boolean] = (k: K) => keyHydrator(k).take(1).compile.last.map(_.isDefined)
    )(implicit ev: Timer[G]) =
      for {
        deferredMap <- DeferredMap.in[F, G].tryableEmpty[K, Option[ExpiringRef[G, EventState[G, E, A]]]]
      } yield new EventStateCache[G, K, E, A] {
        def use[B](k: K)(f: EventState[G, E, A] => G[B]): G[Option[B]] = {
          val getEph = deferredMap.getOrAddF(k) {
            val hydrateStream = keyHydrator(k)
            for {
              wasHydratedRef <- Ref[G].of(false)
              es <-
                EventState[G]
                  .hydrated(initializer(k), hydrateStream.evalTap(_ => wasHydratedRef.set(true)))(eventProcessor)
              wasHydrated <- wasHydratedRef.get
              result <-
                if (wasHydrated) {
                  //Entity does exist
                  ExpiringRef[G]
                    .timed(es, dur)
                    .flatTap { newEph =>
                      Concurrent[G].start(newEph.expired >> deferredMap.del(k))
                    }
                    .map(_.some)
                } else {
                  //Entity does not exist
                  Option.empty.pure[G]
                }
            } yield result
          }
          getEph.flatMap {
            case Some(eph) => eph.use(f)
            case None      => Option.empty.pure[G].flatTap(_ => deferredMap.del(k))
          }
        }
        def add(k: K): G[Boolean] = {
          existenceCheck(k).ifM(
            {
              false.pure[G]
            }, {

              deferredMap
                .tryGet(k)
                .flatMap {
                  case Some(Some(_)) =>
                    false.pure[G]
                  case _ =>
                    for {
                      es <- EventState[G].initial[E, A](initializer(k))(eventProcessor)
                      eph <- ExpiringRef[G].timed(es, dur)
                      _ <- deferredMap.addPure(k)(eph.some)
                      _ <- Concurrent[G].start(eph.expired >> deferredMap.del(k))
                    } yield true
                }

            }
          )
        }
        val hookup: Pipe[G, (K, E), (K, Option[A])] = { s =>
          s.evalMap { case (k, e) => use(k)(es => es.doNext(e)).map(k -> _) }
        }
      }
  }

  /** A set of constructors for `EventStateCache` using the same effect type for everything. */
  def apply[F[_]: Concurrent] = new EventStateCachePartiallyApplied[F, F]

  /** A set of constructors for `EventStateCache` where you can use a different effect for your internal `EventStateCache`. */
  def in[F[_]: Sync, G[_]: Concurrent] = new EventStateCachePartiallyApplied[F, G]
}
