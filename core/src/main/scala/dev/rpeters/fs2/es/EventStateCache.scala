package dev.rpeters.fs2.es

import cats.{Applicative, Functor}
import cats.implicits._
import cats.effect.{Concurrent, Sync, Timer}
import cats.effect.concurrent.{Deferred, Ref}
import fs2.{Pipe, Stream}
import data._
import syntax._

import scala.concurrent.duration._

/** Caches EventState values by key, allowing you to use event-sourced state repeatedly. */
sealed trait EventStateCache[F[_], K, E, A] {

  /** Access some state by key if it exists in your event log.
    *
    * If state is in-memory, it is immediately accessed and passed to your function.
    * If state is not in-memory but exists in the event log, it is rebuilt from your event log, cached, and then passed to your function.
    * If state does not exist at all, returns `None`.
    *
    * @param k The key of your event-sourced state value.
    * @param f A function you want to apply to your state.
    * @return A value derived from the current state at this key, if it exists.
    */
  def use[B](k: K)(f: A => F[B]): F[Option[B]]

  /** Access some state by key if it exists in your event log without caching it.
    *
    * Compared to `use`, has the same semantics as far as loading from the event log goes, but the final state is not cached in-memory.
    * Useful for one-off accesses that you know for sure will not be frequent.
    *
    * @param k The key of your event-sourced state value.
    * @param f A function you want to apply to your state.
    * @return A value derived from the current state at this key, if it exists.
    */
  def useDontCache[B](k: K)(f: A => F[B]): F[Option[B]]

  /** Applies an event to your event log, and then to any in-memory states, without creating new states.
    *
    * If you would like newly-initialized states to be cached in-memory, use `addAndCache` instead.
    *
    * @param e The event you are applying to your event log and state.
    * @return Either the resulting in-memory state, if it has been modified, or `None`.
    */
  def addOnlyCached(e: E): F[Option[A]]

  /** Applies an event to your event log, and then to any in-memory states.
    * Will cache a newly initialized state in-memory. If you only want to modify in-memory state without caching new values, use `add` instead.
    *
    * @param e The event you are applying to your event log and state.
    * @return Either the resulting state in your cache, or an `EmptyState` specifying why the event did not apply.
    */
  def addAndCache(e: E): F[Either[EmptyState, A]]

  /** Applies an event to only the event log, deleting any key that might have matched the event from your cache.
    * After calling this, If you try to use state that this event maps to, you will reload state from the event log on the next access.
    * This is to ensure internal consistency and the event log as your source of truth.
    */
  def addQuick(e: E): F[Unit]

  /** Applies a stream of new events, first to the event log and then in-memory states.
    * If any events do not apply to a state that is currently in-memory, an `EmptyState` is emitted
    */
  val hookupOnlyCached: Pipe[F, E, Option[A]] = _.evalMap(addOnlyCached)

  /** Applies a stream of new events, first to the event log, and then to any in-memory states.
    * Will cache newly initialized state in-memory. If you only want to modify in-memory state without caching new values, use `hookupOnlyCached` instead.
    */
  val hookupAndCache: Pipe[F, E, Either[EmptyState, A]] = _.evalMap(addAndCache)

  /** Applies a stream of new events only to the event log, disregarding state.
    * For consistency, every state that matches the key from your events will be removed from the cache.
    * This ensures that, if you try to use state that these events map to, you will get the correct state back.
    */
  val hookupQuick: Pipe[F, E, Unit] = _.evalMap(addQuick)

  /** Applies a stream of new events, first to the event log, and then to any in-memory states.
    *
    * Similar to `hookup` except it gives you the event that resulted in that state.
    */
  def hookupOnlyCachedWithInput(implicit F: Functor[F]): Pipe[F, E, (E, Option[A])] =
    _.evalMap(e => addOnlyCached(e).tupleLeft(e))

  /** Applies a stream of new events, first to the event log, and then to any in-memory states.
    * Will cache newly initialized state in-memory. If you only want to modify in-memory state without caching new values, use `hookupWithInput` instead.
    *
    * Similar to `hookupAndCache` except it gives you the event that resulted in that state.
    */
  def hookupAndCacheWithInput(implicit F: Functor[F]): Pipe[F, E, (E, Either[EmptyState, A])] =
    _.evalMap(e => addAndCache(e).tupleLeft(e))

  /** Applies a stream of new events, deleting any in-memory states they would apply to by-key, and returns the input event.
    * For consistency, every state that matches the key from your events will be removed from the cache.
    * This ensures that, if you try to use state that these events map to, you will get the correct state back.
    */
  def hookupQuickWithInput(implicit F: Functor[F]): Pipe[F, E, E] = _.evalMap(e => addQuick(e).as(e))
}

object EventStateCache {

  final class EventStateCachePartiallyApplied[F[_]: Sync, G[_]: Concurrent]() {

    /** Create an EventStateCache backed by the supplied event log.
      * As many as `maxStates` states are kept in-memory at once, with the least-recently-used ones removed if that limit is reached.
      * States are also kept in-memory for the specified duration of time, and are then discarded.
      * The next time a state is requested, if it has expired or been removed after hitting the max bound, it is restored from your event log.
      *
      * @param log Your event log you are restoring state from.
      * @param maxStates The maximum number of elements to try to keep in memory at once. Initial value is an arbitrary limit of 1024.
      * @param ttl The ttlation for states to remain in memory. Default is 2 minutes.
      * @param existenceCheck A function to quickly determine if a state exists or not before consulting the event log.
      * @return A new `EventStateCache` that loads states into memory and temporarily caches them.
      */
    def timedBounded[K, E, A](
        log: EventLog[G, E, E],
        maxStates: Int = 1024,
        ttl: FiniteDuration = 2.minutes,
        existenceCheck: K => G[Boolean] = (_: K) => true.pure[G]
    )(implicit keyedState: KeyedState[K, E, A], timer: Timer[G]) = for {
      deferredMap <- DeferredMap.in[F, G].empty[K, Option[ExpiringRef[G, EventState[G, E, Option[A]]]]]
      lru <- LRU.in[F, G, K]
    } yield new EventStateCache[G, K, E, A] {
      private def notFound[A]: Either[EmptyState, A] = EmptyState.NotFound.asLeft
      private def deleted[A]: Either[EmptyState, A] = EmptyState.Deleted.asLeft
      private def lruUse(k: K) = lru.use(k).flatMap { count =>
        if (count > maxStates) lru.pop.flatMap(_.map(deferredMap.del).map(_.void).getOrElse(Applicative[G].unit)).void
        else Applicative[G].unit
      }
      private def useEventState[B](k: K)(f: EventState[G, E, Option[A]] => G[B]): G[Option[B]] = {
        val doTheThing: G[Option[B]] = deferredMap
          .getOrAddF(k) {
            log.getOneState[K, A](k).unNone.compile.last.flatMap {
              case Some(state) =>
                EventState[G].initial[E, A](state).flatMap { es =>
                  ExpiringRef[G]
                    .timed(es, ttl)
                    .flatTap(exp => Concurrent[G].start(exp.expired >> lru.del(k) >> deferredMap.del(k)))
                    .map(_.some)
                }
              case None =>
                lru.del(k) >> deferredMap.del(k).as(none)
            }
          }
          .flatMap {
            case Some(exp) =>
              lruUse(k) >> exp.use(f)
            case None =>
              none.pure[G]
          }

        existenceCheck(k).ifM(
          doTheThing,
          lru.del(k) >> deferredMap.del(k).as(none)
        )
      }

      def use[B](k: K)(f: A => G[B]): G[Option[B]] = {
        useEventState(k)(_.get.flatMap(_.traverse(f))).map {
          case Some(Some(v)) => v.some
          case _             => none
        }
      }

      def useDontCache[B](k: K)(f: A => G[B]): G[Option[B]] = {
        deferredMap.getOpt(k).flatMap {
          case Some(Some(exp)) => exp.use(e => lruUse(k) >> e.get.flatMap(_.traverse(f))).map(_.flatten)
          case _ =>
            val getFromLog: G[Option[B]] = log.getOneState[K, A](k).unNone.compile.last.flatMap {
              case Some(v) => f(v).map(_.some)
              case None    => none.pure[G]
            }
            val del = lru.del(k) >> deferredMap.del(k).as(none[B])
            existenceCheck(k).ifM(getFromLog, del)
        }
      }

      def addOnlyCached(e: E): G[Option[A]] =
        deferredMap
          .getOpt(e.getKey)
          .flatMap {
            case Some(Some(exp)) =>
              exp.use(es => log.add(e) >> lruUse(e.getKey) >> es.doNext(e)).flatMap {
                case Some(Some(v)) => v.some.pure[G] //State exists in cache
                case Some(None) =>
                  lru.del(e.getKey) >> deferredMap.del(e.getKey).as(none) //Key was deleted after applying event
                case None => log.add(e).as(none) //State does not exist in cache
              }
            case Some(None) => log.add(e) >> lru.del(e.getKey) >> deferredMap.del(e.getKey).as(none) //State is expired
            case None       => log.add(e).as(none) //Nothing exists in cache
          }

      def addAndCache(e: E): G[Either[EmptyState, A]] =
        useEventState(e.getKey)(es => log.add(e) >> lruUse(e.getKey) >> es.doNext(e)).flatMap {
          case Some(Some(v)) => v.asRight.pure[G] //State exists in cache
          case Some(None) =>
            lru.del(e.getKey) >> deferredMap.del(e.getKey).as(deleted) //Key was deleted after applying event
          case None =>
            //State does not exist in cache
            log.add(e).flatMap { _ =>
              None.handleEvent(e) match {
                case Some(v) =>
                  for {
                    d <- Deferred.tryable[G, Option[ExpiringRef[G, EventState[G, E, Option[A]]]]]
                    es <- EventState[G].initial[E, A](v)
                    _ <- lruUse(e.getKey)
                    expref <- ExpiringRef[G].timed(es, ttl)
                    _ <- d.complete(expref.some)
                    _ <- deferredMap.add(e.getKey)(d)
                  } yield v.asRight
                case None => notFound.pure[G]
              }
            }
        }

      def addQuick(e: E): G[Unit] = log.add(e) >> lru.del(e.getKey) >> deferredMap.del(e.getKey).void

    }

    /** Create an EventStateCache backed by the supplied event log.
      * Buffers up to `maxStates` states in-memory at once, with the least-recently-used ones removed if that limit is reached.
      * The next time a state is requested after being removed, it is restored from your event log.
      *
      * @param log Your event log you are restoring state from.
      * @param maxStates The maximum number of elements to try to keep in memory at once. Initial value is an arbitrary limit of 1024.
      * @param ttl The ttlation for states to remain in memory. Default is 2 minutes.
      * @param existenceCheck A function to quickly determine if a state exists or not before consulting the event log.
      * @return A new `EventStateCache` that loads states into memory and temporarily caches them.
      */
    def bounded[K, E, A](
        log: EventLog[G, E, E],
        maxStates: Int = 1024,
        existenceCheck: K => G[Boolean] = (_: K) => true.pure[G]
    )(implicit keyedState: KeyedState[K, E, A]) = for {
      deferredMap <- DeferredMap.in[F, G].empty[K, Option[EventState[G, E, Option[A]]]]
      lru <- LRU.in[F, G, K]
    } yield new EventStateCache[G, K, E, A] {
      private def notFound[A]: Either[EmptyState, A] = EmptyState.NotFound.asLeft
      private def deleted[A]: Either[EmptyState, A] = EmptyState.Deleted.asLeft
      private def lruUse(k: K) = lru.use(k).flatMap { count =>
        if (count > maxStates) lru.pop.flatMap(_.map(deferredMap.del).map(_.void).getOrElse(Applicative[G].unit)).void
        else Applicative[G].unit
      }
      private def useEventState[B](k: K)(f: EventState[G, E, Option[A]] => G[B]): G[Option[B]] = {
        val doTheThing: G[Option[B]] = deferredMap
          .getOrAddF(k) {
            log.getOneState[K, A](k).unNone.compile.last.flatMap {
              case Some(state) =>
                EventState[G].initial[E, A](state).flatMap { es =>
                  lruUse(k).as(es.some)
                }
              case None =>
                deferredMap.del(k).as(none)
            }
          }
          .flatMap {
            case Some(es) =>
              f(es).map(_.some)
            case None =>
              none.pure[G]
          }

        existenceCheck(k).ifM(
          doTheThing,
          deferredMap.del(k).as(none)
        )
      }

      def use[B](k: K)(f: A => G[B]): G[Option[B]] = {
        useEventState(k)(_.get.flatMap(_.traverse(f))).flatMap {
          case Some(Some(v)) => v.some.pure[G]
          case _             => none.pure[G]
        }
      }

      def useDontCache[B](k: K)(f: A => G[B]): G[Option[B]] = {
        deferredMap.getOpt(k).flatMap {
          case Some(Some(es)) => lruUse(k) >> es.get.flatMap(_.traverse(f))
          case _ =>
            val getFromLog: G[Option[B]] = log.getOneState[K, A](k).unNone.compile.last.flatMap {
              case Some(v) => f(v).map(_.some)
              case None    => none.pure[G]
            }
            val del = lru.del(k) >> deferredMap.del(k).as(none[B])
            existenceCheck(k).ifM(getFromLog, del)
        }
      }

      def addOnlyCached(e: E): G[Option[A]] =
        deferredMap
          .getOpt(e.getKey)
          .flatMap {
            case Some(Some(es)) =>
              log.add(e) >> lruUse(e.getKey) >> es.doNext(e).flatMap {
                case Some(v) => v.some.pure[G] //State exists in cache
                case None    => deferredMap.del(e.getKey).as(none) //Key was deleted after applying event
              }
            case Some(None) => log.add(e) >> lru.del(e.getKey) >> deferredMap.del(e.getKey).as(none)
            case None       => log.add(e).as(none)
          }

      def addAndCache(e: E): G[Either[EmptyState, A]] =
        useEventState(e.getKey)(es => log.add(e) >> es.doNext(e)).flatMap {
          case Some(Some(v)) => v.asRight.pure[G] //State exists in cache
          case Some(None) =>
            lru.del(e.getKey) >> deferredMap.del(e.getKey).as(deleted) //Key was deleted after applying event
          case None =>
            //State does not exist in cache
            log.add(e).flatMap { _ =>
              None.handleEvent(e) match {
                case Some(v) =>
                  for {
                    d <- Deferred.tryable[G, Option[EventState[G, E, Option[A]]]]
                    es <- EventState[G].initial[E, A](v)
                    _ <- lru.use(e.getKey)
                    _ <- d.complete(es.some)
                    _ <- deferredMap.add(e.getKey)(d)
                  } yield v.asRight
                case None => notFound.pure[G]
              }
            }
        }

      def addQuick(e: E): G[Unit] = log.add(e) >> lru.del(e.getKey) >> deferredMap.del(e.getKey).void

    }

    /** Create an EventStateCache backed by the supplied event log.
      * Buffers up to `maxStates` states in-memory at once, with the least-recently-used ones removed if that limit is reached.
      * The next time a state is requested after being removed, it is restored from your event log.
      *
      * @param log Your event log you are restoring state from.
      * @param maxStates The maximum number of elements to try to keep in memory at once. Initial value is an arbitrary limit of 1024.
      * @param ttl The ttlation for states to remain in memory. Default is 2 minutes.
      * @param existenceCheck A function to quickly determine if a state exists or not before consulting the event log.
      * @return A new `EventStateCache` that loads states into memory and temporarily caches them.
      */
    def unbounded[K, E, A](
        log: EventLog[G, E, E],
        existenceCheck: K => G[Boolean] = (_: K) => true.pure[G]
    )(implicit keyedState: KeyedState[K, E, A]) = for {
      deferredMap <- DeferredMap.in[F, G].empty[K, Option[EventState[G, E, Option[A]]]]
    } yield new EventStateCache[G, K, E, A] {
      private def notFound[A]: Either[EmptyState, A] = EmptyState.NotFound.asLeft
      private def deleted[A]: Either[EmptyState, A] = EmptyState.Deleted.asLeft
      private def useEventState[B](k: K)(f: EventState[G, E, Option[A]] => G[B]): G[Option[B]] = {
        val doTheThing: G[Option[B]] = deferredMap
          .getOrAddF(k) {
            log.getOneState[K, A](k).unNone.compile.last.flatMap {
              case Some(state) =>
                EventState[G].initial[E, A](state).map(_.some)
              case None =>
                deferredMap.del(k).as(none)
            }
          }
          .flatMap {
            case Some(es) =>
              f(es).map(_.some)
            case None =>
              none.pure[G]
          }

        existenceCheck(k).ifM(
          doTheThing,
          deferredMap.del(k).as(none)
        )
      }

      def use[B](k: K)(f: A => G[B]): G[Option[B]] = {
        useEventState(k)(_.get.flatMap(_.traverse(f))).flatMap {
          case Some(Some(v)) => v.some.pure[G]
          case _             => none.pure[G]
        }
      }

      def useDontCache[B](k: K)(f: A => G[B]): G[Option[B]] = {
        deferredMap.getOpt(k).flatMap {
          case Some(Some(es)) => es.get.flatMap(_.traverse(f))
          case _ =>
            val getFromLog: G[Option[B]] = log.getOneState[K, A](k).unNone.compile.last.flatMap {
              case Some(v) => f(v).map(_.some)
              case None    => none.pure[G]
            }
            val del = deferredMap.del(k).as(none[B])
            existenceCheck(k).ifM(getFromLog, del)
        }
      }

      def addOnlyCached(e: E): G[Option[A]] =
        deferredMap
          .getOpt(e.getKey)
          .flatMap {
            case Some(Some(es)) =>
              log.add(e) >> es.doNext(e).flatMap {
                case Some(v) => v.some.pure[G] //State exists in cache
                case None    => deferredMap.del(e.getKey).as(none) //Key was deleted after applying event
              }
            case Some(None) => log.add(e) >> deferredMap.del(e.getKey).as(none)
            case None       => log.add(e).as(none)
          }

      def addAndCache(e: E): G[Either[EmptyState, A]] =
        useEventState(e.getKey)(es => log.add(e) >> es.doNext(e)).flatMap {
          case Some(Some(v)) => v.asRight.pure[G] //State exists in cache
          case Some(None)    => deferredMap.del(e.getKey).as(deleted) //Key was deleted after applying event
          case None          =>
            //State does not exist in cache
            log.add(e).flatMap { _ =>
              None.handleEvent(e) match {
                case Some(v) =>
                  for {
                    d <- Deferred.tryable[G, Option[EventState[G, E, Option[A]]]]
                    es <- EventState[G].initial[E, A](v)
                    _ <- d.complete(es.some)
                    _ <- deferredMap.add(e.getKey)(d)
                  } yield v.asRight
                case None => notFound.pure[G]
              }
            }
        }

      def addQuick(e: E): G[Unit] = log.add(e) >> deferredMap.del(e.getKey).void

    }

    /** Create an EventStateCache backed by the supplied event log.
      * States are kept in-memory for the specified duration of time, and are then discarded.
      * The next time a state is requested, it is restored from your event log.
      *
      * @param log Your event log you are restoring state from.
      * @param ttl The ttlation for states to remain in memory. Default is 2 minutes.
      * @param existenceCheck A function to quickly determine if a state exists or not before consulting the event log.
      * @return A new `EventStateCache` that loads states into memory and temporarily caches them.
      */
    def timed[K, E, A](
        log: EventLog[G, E, E],
        ttl: FiniteDuration = 2.minutes,
        existenceCheck: K => G[Boolean] = (_: K) => true.pure[G]
    )(implicit keyedState: KeyedState[K, E, A], timer: Timer[G]) = for {
      deferredMap <- DeferredMap.in[F, G].empty[K, Option[ExpiringRef[G, EventState[G, E, Option[A]]]]]
    } yield new EventStateCache[G, K, E, A] {
      private def notFound[A]: Either[EmptyState, A] = EmptyState.NotFound.asLeft
      private def deleted[A]: Either[EmptyState, A] = EmptyState.Deleted.asLeft
      private def useEventState[B](k: K)(f: EventState[G, E, Option[A]] => G[B]): G[Option[B]] = {
        val doTheThing: G[Option[B]] = deferredMap
          .getOrAddF(k) {
            log.getOneState[K, A](k).unNone.compile.last.flatMap {
              case Some(state) =>
                EventState[G].initial[E, A](state).flatMap { es =>
                  ExpiringRef[G]
                    .timed(es, ttl)
                    .flatTap(exp => Concurrent[G].start(exp.expired >> deferredMap.del(k)))
                    .map(_.some)
                }
              case None =>
                deferredMap.del(k).as(none)
            }
          }
          .flatMap {
            case Some(exp) =>
              exp.use(f)
            case None =>
              none.pure[G]
          }

        existenceCheck(k).ifM(
          doTheThing,
          deferredMap.del(k).as(none)
        )
      }

      def use[B](k: K)(f: A => G[B]): G[Option[B]] = {
        useEventState(k)(_.get.flatMap(_.traverse(f))).flatMap {
          case Some(Some(v)) => v.some.pure[G]
          case _             => none.pure[G]
        }
      }

      def useDontCache[B](k: K)(f: A => G[B]): G[Option[B]] = {
        deferredMap.getOpt(k).flatMap {
          case Some(Some(exp)) => exp.use(_.get.flatMap(_.traverse(f))).map(_.flatten)
          case _ =>
            val getFromLog: G[Option[B]] = log.getOneState[K, A](k).unNone.compile.last.flatMap {
              case Some(v) => f(v).map(_.some)
              case None    => none.pure[G]
            }
            val del = deferredMap.del(k).as(none[B])
            existenceCheck(k).ifM(getFromLog, del)
        }
      }

      def addOnlyCached(e: E): G[Option[A]] =
        deferredMap
          .getOpt(e.getKey)
          .flatMap {
            case Some(Some(exp)) =>
              exp.use(es => log.add(e) >> es.doNext(e)).flatMap {
                case Some(Some(v)) => v.some.pure[G] //State exists in cache
                case Some(None)    => deferredMap.del(e.getKey).as(none) //Key was deleted after applying event
                case None          => log.add(e).as(none) //State does not exist in cache
              }
            case Some(None) => log.add(e) >> deferredMap.del(e.getKey).as(none)
            case None       => log.add(e).as(none)
          }

      def addAndCache(e: E): G[Either[EmptyState, A]] =
        useEventState(e.getKey)(es => log.add(e) >> es.doNext(e)).flatMap {
          case Some(Some(v)) => v.asRight.pure[G] //State exists in cache
          case Some(None)    => deferredMap.del(e.getKey).as(deleted) //Key was deleted after applying event
          case None          =>
            //State does not exist in cache
            log.add(e).flatMap { _ =>
              None.handleEvent(e) match {
                case Some(v) =>
                  for {
                    d <- Deferred.tryable[G, Option[ExpiringRef[G, EventState[G, E, Option[A]]]]]
                    es <- EventState[G].initial[E, A](v)
                    expref <- ExpiringRef[G].timed(es, ttl)
                    _ <- d.complete(expref.some)
                    _ <- deferredMap.add(e.getKey)(d)
                  } yield v.asRight
                case None => notFound.pure[G]
              }
            }
        }

      def addQuick(e: E): G[Unit] = log.add(e) >> deferredMap.del(e.getKey).void

    }
  }

  /** A set of constructors for `EventStateCache` using the same effect type for everything. */
  def apply[F[_]: Concurrent] = new EventStateCachePartiallyApplied[F, F]

  /** A set of constructors for `EventStateCache` where you can use a different effect for your internal `EventStateCache`. */
  def in[F[_]: Sync, G[_]: Concurrent] = new EventStateCachePartiallyApplied[F, G]
}
