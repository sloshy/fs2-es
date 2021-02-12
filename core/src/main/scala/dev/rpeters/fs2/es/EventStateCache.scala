package dev.rpeters.fs2.es

import cats.implicits._
import cats.effect.{Concurrent, Sync, Timer}
import cats.effect.concurrent.Ref
import fs2.{Pipe, Stream}
import data._
import syntax._

import scala.concurrent.duration._

/** Caches EventState values by key, allowing you to use event-sourced state repeatedly. */
sealed trait EventStateCache[F[_], K, E, A] {

  /** Access some state by key if it exists in your event log.
    *
    * If state is in-memory, it is immediately accessed and passed to your function.
    * If state is in-memory but "deleted", immediately returns `None`.
    * If state is not in-memory but exists in the event log, it is rebuilt from your event log and then passed to your function.
    *
    * @param k The key of your event-sourced state.
    * @param f A function you want to apply to your entity state.
    * @return `None` if the state was deleted or not found, `Some(b)` if your state exists and your function applied successfully.
    */
  def use[B](k: K)(f: A => F[B]): F[Option[B]]

  /** Applies an event to your event log, and then to any in-memory states.
    *
    * @param e The event you are applying to your event log and state.
    * @return `None` if the state was deleted or not found, `Some(a)` if your state exists or was created from this event.
    */
  def add(e: E): F[Option[A]]

  /** Applies a stream of new events, first to the event log and then in-memory states.
    *
    * This is an `fs2.Pipe` which is an alias for a function `Stream[F, E] => Stream[F, Option[A]]`.
    * By passing a stream of events to this `EventStateCache`,
    * you will keep any in-memory states updated as well as get a stream of all resulting states.
    */
  val hookup: Pipe[F, E, Option[A]] = _.evalMap(e => add(e))
}

object EventStateCache {

  final class EventStateCachePartiallyApplied[F[_]: Sync, G[_]: Concurrent]() {

    /** Create an EventStateCache backed by the given `EventLog`.
      * States that are used are kept in-memory for the specified duration between-uses, before they are removed.
      *
      * @param log Your event log you are restoring state from.
      * @param dur The duration for states to remain in memory. Default is 2 minutes.
      * @param existenceCheck A function to quickly determine if a state exists or not before consulting the event log.
      * @return A new `EventStateCache` that loads states into memory and temporarily caches them.
      */
    def fromEventLog[K, E, A](
        log: EventLog[G, E, E],
        dur: FiniteDuration = 2.minutes,
        existenceCheck: K => G[Boolean] = (_: K) => true.pure[G]
    )(implicit driven: DrivenInitial[E, A], keyed: Keyed[K, E], timer: Timer[G]) = for {
      deferredMap <- DeferredMap.in[F, G].tryableEmpty[K, Option[ExpiringRef[G, EventState[G, E, Option[A]]]]]
    } yield new EventStateCache[G, K, E, A] {
      private def useEventState[B](k: K)(f: EventState[G, E, Option[A]] => G[B]): G[Option[B]] = {
        val doTheThing = deferredMap
          .getOrAddF(k) {
            log.getOneState[A, K](k).unNone.compile.last.flatMap {
              case Some(state) =>
                EventState[G].initial[E, A](state).flatMap { es =>
                  ExpiringRef[G]
                    .timed(es, dur)
                    .flatTap(eph => Concurrent[G].start(eph.expired >> deferredMap.del(k)))
                    .map(_.some)
                }
              case None =>
                none.pure[G]
            }
          }
          .flatMap {
            case Some(eph) => eph.use(f)
            case None      => none[B].pure[G].flatTap(_ => deferredMap.del(k))
          }

        existenceCheck(k).ifM(doTheThing, none.pure[G].flatTap(_ => deferredMap.del(k)))
      }

      def use[B](k: K)(f: A => G[B]): G[Option[B]] = {
        useEventState(k)(_.get.flatMap(_.traverse(f))).map(_.flatten)
      }

      def add(e: E): G[Option[A]] = useEventState(e.getKey)(es => log.add(e) >> es.doNext(e)).flatMap {
        case Some(s) => s.pure[G]
        case None    => log.add(e) >> deferredMap.del(e.getKey).as(none)
      }

    }
  }

  /** A set of constructors for `EventStateCache` using the same effect type for everything. */
  def apply[F[_]: Concurrent] = new EventStateCachePartiallyApplied[F, F]

  /** A set of constructors for `EventStateCache` where you can use a different effect for your internal `EventStateCache`. */
  def in[F[_]: Sync, G[_]: Concurrent] = new EventStateCachePartiallyApplied[F, G]
}
