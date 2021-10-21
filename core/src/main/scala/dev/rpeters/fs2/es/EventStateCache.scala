package dev.rpeters.fs2.es

import cats.{Applicative, Functor}
import cats.ApplicativeThrow
import cats.arrow.Profunctor
import cats.Contravariant
import cats.data.NonEmptySet
import cats.effect.Concurrent
import cats.effect.kernel.{Spawn, Deferred, Ref, Temporal}
import cats.syntax.all._
import data._
import fs2.{Pipe, Stream}

import scala.concurrent.duration._
import syntax._

/** Caches EventState values by key, allowing you to use event-sourced state repeatedly. */
trait EventStateCache[F[_], K, E, A] { self =>

  /** Access some state by key if it exists in your event log.
    *
    * If state is in-memory, it is immediately accessed and passed to your function. If state is not in-memory but
    * exists in the event log, it is rebuilt from your event log, cached, and then passed to your function. If state
    * does not exist at all, returns `None`.
    *
    * @param k
    *   The key of your event-sourced state value.
    * @param f
    *   A function you want to apply to your state.
    * @return
    *   A value derived from the current state at this key, if it exists.
    */
  def use[B](k: K)(f: A => F[B]): F[Option[B]]

  /** Access some state by key if it exists in your event log without caching it if it is not already cached.
    *
    * Compared to `use`, has the same semantics as far as loading from the event log goes, but the final state is not
    * cached in-memory. Useful for one-off accesses that you know for sure will not be frequent.
    *
    * @param k
    *   The key of your event-sourced state value.
    * @param f
    *   A function you want to apply to your state.
    * @return
    *   A value derived from the current state at this key, if it exists.
    */
  def useDontCache[B](k: K)(f: A => F[B]): F[Option[B]]

  /** Applies an event to your event log, and then to any in-memory states. If necessary, will try to load state from
    * the event log and cache state.
    *
    * If you would like newly-initialized states to be cached in-memory, use `addAndCache` instead.
    *
    * @param e
    *   The event you are applying to your event log and state.
    * @return
    *   Either the resulting in-memory state, if it has been modified, or `None`.
    */
  def addOnlyCached(e: E): F[Option[A]]

  /** Applies an event to your event log, and then to any in-memory states. Will cache a newly initialized state
    * in-memory. If you only want to modify in-memory state without caching new values, use `add` instead.
    *
    * @param e
    *   The event you are applying to your event log and state.
    * @return
    *   Either the resulting state in your cache, or an `EmptyState` specifying why the event did not apply.
    */
  def addAndCache(e: E): F[Either[EmptyState, A]]

  /** Applies an event to only the event log, removing any key that might have matched the event from your cache. After
    * calling this, If you try to use state that this event maps to, you will reload state from the event log on the
    * next access. This is to ensure internal consistency and the event log as your source of truth.
    *
    * @param e
    *   The event you are applying to your event log.
    */
  def addAndRemove(e: E): F[Unit]

  /** Loads the specified keys into memory and returns their states if they exist. */
  def loadBatch(keys: NonEmptySet[K]): F[Map[K, A]]

  /** Applies a stream of new events, first to the event log and then in-memory states. If any events do not apply to a
    * state that is currently in-memory, an `EmptyState` is emitted
    */
  val hookupOnlyCached: Pipe[F, E, Option[A]] = _.evalMap(addOnlyCached)

  /** Applies a stream of new events, first to the event log, and then to any in-memory states. Will cache newly
    * initialized state in-memory. If you only want to modify in-memory state without caching new values, use
    * `hookupOnlyCached` instead.
    */
  val hookupAndCache: Pipe[F, E, Either[EmptyState, A]] = _.evalMap(addAndCache)

  /** Applies a stream of new events only to the event log, disregarding state. For consistency, every state that
    * matches the key from your events will be removed from the cache. This ensures that, if you try to use state that
    * these events map to, you will get the correct state back.
    */
  val hookupQuick: Pipe[F, E, Unit] = _.evalMap(addAndRemove)

  /** Applies a stream of new events, first to the event log, and then to any in-memory states.
    *
    * Similar to `hookup` except it gives you the event that resulted in that state.
    */
  def hookupOnlyCachedWithInput(implicit F: Functor[F]): Pipe[F, E, (E, Option[A])] =
    _.evalMap(e => addOnlyCached(e).tupleLeft(e))

  /** Applies a stream of new events, first to the event log, and then to any in-memory states. Will cache newly
    * initialized state in-memory. If you only want to modify in-memory state without caching new values, use
    * `hookupWithInput` instead.
    *
    * Similar to `hookupAndCache` except it gives you the event that resulted in that state.
    */
  def hookupAndCacheWithInput(implicit F: Functor[F]): Pipe[F, E, (E, Either[EmptyState, A])] =
    _.evalMap(e => addAndCache(e).tupleLeft(e))

  /** Applies a stream of new events, deleting any in-memory states they would apply to by-key, and returns the input
    * event. For consistency, every state that matches the key from your events will be removed from the cache. This
    * ensures that, if you try to use state that these events map to, you will get the correct state back.
    */
  def hookupQuickWithInput(implicit F: Functor[F]): Pipe[F, E, E] = _.evalMap(e => addAndRemove(e).as(e))
}

object EventStateCache {

  final class InMemoryEventStateCachePartiallyApplied[F[_]: Concurrent]() {

    /** Create an in-memory EventStateCache backed by the supplied event log. As many as `maxStates` states are kept
      * in-memory at once, with the least-recently-used ones removed if that limit is reached. States are also kept
      * in-memory for the specified duration of time, and are then discarded. The next time a state is requested, if it
      * has expired or been removed after hitting the max bound, it is restored from your event log.
      *
      * @param log
      *   Your event log you are restoring state from.
      * @param maxStates
      *   The maximum number of elements to try to keep in memory at once. Initial value is an arbitrary limit of 1024.
      * @param ttl
      *   The ttlation for states to remain in memory. Default is 2 minutes.
      * @param existenceCheck
      *   A function to quickly determine if a state exists or not before consulting the event log.
      * @return
      *   A new `EventStateCache` that loads states into memory and temporarily caches them.
      */
    def timedBounded[K, E, A](
        log: KeyedFiniteEventLog[F, K, E, E],
        maxStates: Int = 1024,
        ttl: FiniteDuration = 2.minutes,
        existenceCheck: K => F[Boolean] = (_: K) => true.pure[F]
    )(implicit keyedState: KeyedState[K, E, A], F: Temporal[F]) = for {
      deferredMap <- DeferredMap[F].empty[K, Option[ExpiringRef[F, EventState[F, E, Option[A]]]]]
      lru <- LRU[F, K]
    } yield new EventStateCache[F, K, E, A] {
      private def notFound[A]: Either[EmptyState, A] = EmptyState.NotFound.asLeft
      private def removed[A]: Either[EmptyState, A] = EmptyState.Removed.asLeft
      private def lruUse(k: K) = lru.use(k).flatMap { count =>
        if (count > maxStates) lru.pop.flatMap(_.map(deferredMap.del).map(_.void).getOrElse(Applicative[F].unit)).void
        else Applicative[F].unit
      }
      private def useEventState[B](k: K)(f: EventState[F, E, Option[A]] => F[B]): F[Option[B]] = {
        val doTheThing: F[Option[B]] = deferredMap
          .getOrAddF(k) {
            log.getOneState[A](k).unNone.compile.last.flatMap {
              case Some(state) =>
                EventState[F].initial[E, A](state).flatMap { es =>
                  ExpiringRef[F]
                    .timed(es, ttl)
                    .flatTap(exp => Spawn[F].start(exp.expired >> lru.del(k) >> deferredMap.del(k)))
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
              none.pure[F]
          }

        existenceCheck(k).ifM(
          doTheThing,
          lru.del(k) >> deferredMap.del(k).as(none)
        )
      }

      def use[B](k: K)(f: A => F[B]): F[Option[B]] = {
        useEventState(k)(_.get.flatMap(_.traverse(f))).map {
          case Some(Some(v)) => v.some
          case _             => none
        }
      }

      def useDontCache[B](k: K)(f: A => F[B]): F[Option[B]] = {
        deferredMap.getOpt(k).flatMap {
          case Some(Some(exp)) => exp.use(e => lruUse(k) >> e.get.flatMap(_.traverse(f))).map(_.flatten)
          case _ =>
            val getFromLog: F[Option[B]] = log.getOneState[A](k).unNone.compile.last.flatMap {
              case Some(v) => f(v).map(_.some)
              case None    => none.pure[F]
            }
            val del = lru.del(k) >> deferredMap.del(k).as(none[B])
            existenceCheck(k).ifM(getFromLog, del)
        }
      }

      def addOnlyCached(e: E): F[Option[A]] =
        deferredMap
          .getOpt(e.getKey)
          .flatMap {
            case Some(Some(exp)) =>
              exp.use(es => log.add(e) >> lruUse(e.getKey) >> es.doNext(e)).flatMap {
                case Some(Some(v)) => v.some.pure[F] //State exists in cache
                case Some(None) =>
                  lru.del(e.getKey) >> deferredMap.del(e.getKey).as(none) //Key was removed after applying event
                case None => log.add(e).as(none) //State does not exist in cache
              }
            case Some(None) => log.add(e) >> lru.del(e.getKey) >> deferredMap.del(e.getKey).as(none) //State is expired
            case None       => log.add(e).as(none) //Nothing exists in cache
          }

      def addAndCache(e: E): F[Either[EmptyState, A]] =
        useEventState(e.getKey)(es => log.add(e) >> lruUse(e.getKey) >> es.doNext(e)).flatMap {
          case Some(Some(v)) => v.asRight.pure[F] //State exists in cache
          case Some(None) =>
            lru.del(e.getKey) >> deferredMap.del(e.getKey).as(removed) //Key was removed after applying event
          case None =>
            //State does not exist in cache
            log.add(e).flatMap { _ =>
              None.handleEvent(e) match {
                case Some(v) =>
                  for { //TODO: reexamine whether or not this properly works concurrently - the second usage should always await on deferredmap
                    d <- Deferred[F, Option[ExpiringRef[F, EventState[F, E, Option[A]]]]]
                    _ <- deferredMap.add(e.getKey)(d)
                    es <- EventState[F].initial[E, A](v)
                    _ <- lruUse(e.getKey)
                    expref <- ExpiringRef[F].timed(es, ttl)
                    _ <- d.complete(expref.some)
                  } yield v.asRight
                case None => notFound.pure[F]
              }
            }
        }

      def addAndRemove(e: E): F[Unit] = log.add(e) >> lru.del(e.getKey) >> deferredMap.del(e.getKey).void

      def loadBatch(keys: NonEmptySet[K]): F[Map[K, A]] = {
        val size = keys.size
        if (size > maxStates) {
          ApplicativeThrow[F].raiseError(
            new IllegalArgumentException(
              s"Cannot load $size keys into memory when you have an upper bound of $maxStates"
            )
          )
        } else {
          log
            .getKeyedStateBatch[A](keys)
            .evalMap { m =>
              m.toList.traverse { case (k, v) =>
                for {
                  d <- Deferred[F, Option[ExpiringRef[F, EventState[F, E, Option[A]]]]]
                  _ <- deferredMap.add(k)(d)
                  es <- EventState[F].initial[E, A](v)
                  _ <- lruUse(k)
                  expref <- ExpiringRef[F].timed(es, ttl)
                  _ <- d.complete(expref.some)
                } yield k -> v
              }
            }
            .compile
            .lastOrError
            .map(_.toMap)
        }
      }
    }

    /** Create an in-memory EventStateCache backed by the supplied event log. Buffers up to `maxStates` states in-memory
      * at once, with the least-recently-used ones removed if that limit is reached. The next time a state is requested
      * after being removed, it is restored from your event log.
      *
      * @param log
      *   Your event log you are restoring state from.
      * @param maxStates
      *   The maximum number of elements to try to keep in memory at once. Initial value is an arbitrary limit of 1024.
      * @param ttl
      *   The ttlation for states to remain in memory. Default is 2 minutes.
      * @param existenceCheck
      *   A function to quickly determine if a state exists or not before consulting the event log.
      * @return
      *   A new `EventStateCache` that loads states into memory and temporarily caches them.
      */
    def bounded[K, E, A](
        log: KeyedFiniteEventLog[F, K, E, E],
        maxStates: Int = 1024,
        existenceCheck: K => F[Boolean] = (_: K) => true.pure[F]
    )(implicit keyedState: KeyedState[K, E, A]) = for {
      deferredMap <- DeferredMap[F].empty[K, Option[EventState[F, E, Option[A]]]]
      lru <- LRU[F, K]
    } yield new EventStateCache[F, K, E, A] {
      private def notFound[A]: Either[EmptyState, A] = EmptyState.NotFound.asLeft
      private def removed[A]: Either[EmptyState, A] = EmptyState.Removed.asLeft
      private def lruUse(k: K) = lru.use(k).flatMap { count =>
        if (count > maxStates) lru.pop.flatMap(_.map(deferredMap.del).map(_.void).getOrElse(Applicative[F].unit)).void
        else Applicative[F].unit
      }
      private def useEventState[B](k: K)(f: EventState[F, E, Option[A]] => F[B]): F[Option[B]] = {
        val doTheThing: F[Option[B]] = deferredMap
          .getOrAddF(k) {
            log.getOneState[A](k).unNone.compile.last.flatMap {
              case Some(state) =>
                EventState[F].initial[E, A](state).flatMap { es =>
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
              none.pure[F]
          }

        existenceCheck(k).ifM(
          doTheThing,
          deferredMap.del(k).as(none)
        )
      }

      def use[B](k: K)(f: A => F[B]): F[Option[B]] = {
        useEventState(k)(_.get.flatMap(_.traverse(f))).flatMap {
          case Some(Some(v)) => v.some.pure[F]
          case _             => none.pure[F]
        }
      }

      def useDontCache[B](k: K)(f: A => F[B]): F[Option[B]] = {
        deferredMap.getOpt(k).flatMap {
          case Some(Some(es)) => lruUse(k) >> es.get.flatMap(_.traverse(f))
          case _ =>
            val getFromLog: F[Option[B]] = log.getOneState[A](k).unNone.compile.last.flatMap {
              case Some(v) => f(v).map(_.some)
              case None    => none.pure[F]
            }
            val del = lru.del(k) >> deferredMap.del(k).as(none[B])
            existenceCheck(k).ifM(getFromLog, del)
        }
      }

      def addOnlyCached(e: E): F[Option[A]] =
        deferredMap
          .getOpt(e.getKey)
          .flatMap {
            case Some(Some(es)) =>
              log.add(e) >> lruUse(e.getKey) >> es.doNext(e).flatMap {
                case Some(v) => v.some.pure[F] //State exists in cache
                case None    => deferredMap.del(e.getKey).as(none) //Key was removed after applying event
              }
            case Some(None) => log.add(e) >> lru.del(e.getKey) >> deferredMap.del(e.getKey).as(none)
            case None       => log.add(e).as(none)
          }

      def addAndCache(e: E): F[Either[EmptyState, A]] =
        useEventState(e.getKey)(es => log.add(e) >> es.doNext(e)).flatMap {
          case Some(Some(v)) => v.asRight.pure[F] //State exists in cache
          case Some(None) =>
            lru.del(e.getKey) >> deferredMap.del(e.getKey).as(removed) //Key was removed after applying event
          case None =>
            //State does not exist in cache
            log.add(e).flatMap { _ =>
              None.handleEvent(e) match {
                case Some(v) =>
                  for {
                    d <- Deferred[F, Option[EventState[F, E, Option[A]]]]
                    _ <- deferredMap.add(e.getKey)(d)
                    es <- EventState[F].initial[E, A](v)
                    _ <- lru.use(e.getKey)
                    _ <- d.complete(es.some)
                  } yield v.asRight
                case None => notFound.pure[F]
              }
            }
        }

      def addAndRemove(e: E): F[Unit] = log.add(e) >> lru.del(e.getKey) >> deferredMap.del(e.getKey).void

      def loadBatch(keys: NonEmptySet[K]): F[Map[K, A]] = {
        val size = keys.size
        if (size > maxStates) {
          ApplicativeThrow[F].raiseError(
            new IllegalArgumentException(
              s"Cannot load $size keys into memory when you have an upper bound of $maxStates"
            )
          )
        } else {
          log
            .getKeyedStateBatch[A](keys)
            .evalMap { m =>
              m.toList.traverse { case (k, v) =>
                for {
                  d <- Deferred[F, Option[EventState[F, E, Option[A]]]]
                  _ <- deferredMap.add(k)(d)
                  es <- EventState[F].initial[E, A](v)
                  _ <- lruUse(k)
                  _ <- d.complete(es.some)
                } yield k -> v
              }
            }
            .compile
            .lastOrError
            .map(_.toMap)
        }
      }

    }

    /** Create an in-memory EventStateCache backed by the supplied event log. Buffers up to `maxStates` states in-memory
      * at once, with the least-recently-used ones removed if that limit is reached. The next time a state is requested
      * after being removed, it is restored from your event log.
      *
      * @param log
      *   Your event log you are restoring state from.
      * @param maxStates
      *   The maximum number of elements to try to keep in memory at once. Initial value is an arbitrary limit of 1024.
      * @param ttl
      *   The ttlation for states to remain in memory. Default is 2 minutes.
      * @param existenceCheck
      *   A function to quickly determine if a state exists or not before consulting the event log.
      * @return
      *   A new `EventStateCache` that loads states into memory and temporarily caches them.
      */
    def unbounded[K, E, A](
        log: KeyedFiniteEventLog[F, K, E, E],
        existenceCheck: K => F[Boolean] = (_: K) => true.pure[F]
    )(implicit keyedState: KeyedState[K, E, A]) = for {
      deferredMap <- DeferredMap[F].empty[K, Option[EventState[F, E, Option[A]]]]
    } yield new EventStateCache[F, K, E, A] {
      private def notFound[A]: Either[EmptyState, A] = EmptyState.NotFound.asLeft
      private def removed[A]: Either[EmptyState, A] = EmptyState.Removed.asLeft
      private def useEventState[B](k: K)(f: EventState[F, E, Option[A]] => F[B]): F[Option[B]] = {
        val doTheThing: F[Option[B]] = deferredMap
          .getOrAddF(k) {
            log.getOneState[A](k).unNone.compile.last.flatMap {
              case Some(state) =>
                EventState[F].initial[E, A](state).map(_.some)
              case None =>
                deferredMap.del(k).as(none)
            }
          }
          .flatMap {
            case Some(es) =>
              f(es).map(_.some)
            case None =>
              none.pure[F]
          }

        existenceCheck(k).ifM(
          doTheThing,
          deferredMap.del(k).as(none)
        )
      }

      def use[B](k: K)(f: A => F[B]): F[Option[B]] = {
        useEventState(k)(_.get.flatMap(_.traverse(f))).flatMap {
          case Some(Some(v)) => v.some.pure[F]
          case _             => none.pure[F]
        }
      }

      def useDontCache[B](k: K)(f: A => F[B]): F[Option[B]] = {
        deferredMap.getOpt(k).flatMap {
          case Some(Some(es)) => es.get.flatMap(_.traverse(f))
          case _ =>
            val getFromLog: F[Option[B]] = log.getOneState[A](k).unNone.compile.last.flatMap {
              case Some(v) => f(v).map(_.some)
              case None    => none.pure[F]
            }
            val del = deferredMap.del(k).as(none[B])
            existenceCheck(k).ifM(getFromLog, del)
        }
      }

      def addOnlyCached(e: E): F[Option[A]] =
        deferredMap
          .getOpt(e.getKey)
          .flatMap {
            case Some(Some(es)) =>
              log.add(e) >> es.doNext(e).flatMap {
                case Some(v) => v.some.pure[F] //State exists in cache
                case None    => deferredMap.del(e.getKey).as(none) //Key was removed after applying event
              }
            case Some(None) => log.add(e) >> deferredMap.del(e.getKey).as(none)
            case None       => log.add(e).as(none)
          }

      def addAndCache(e: E): F[Either[EmptyState, A]] =
        useEventState(e.getKey)(es => log.add(e) >> es.doNext(e)).flatMap {
          case Some(Some(v)) => v.asRight.pure[F] //State exists in cache
          case Some(None)    => deferredMap.del(e.getKey).as(removed) //Key was removed after applying event
          case None          =>
            //State does not exist in cache
            log.add(e).flatMap { _ =>
              None.handleEvent(e) match {
                case Some(v) =>
                  for {
                    d <- Deferred[F, Option[EventState[F, E, Option[A]]]]
                    _ <- deferredMap.add(e.getKey)(d)
                    es <- EventState[F].initial[E, A](v)
                    _ <- d.complete(es.some)
                  } yield v.asRight
                case None => notFound.pure[F]
              }
            }
        }

      def addAndRemove(e: E): F[Unit] = log.add(e) >> deferredMap.del(e.getKey).void

      def loadBatch(keys: NonEmptySet[K]): F[Map[K, A]] =
        log
          .getKeyedStateBatch[A](keys)
          .evalMap { m =>
            m.toList.traverse { case (k, v) =>
              for {
                d <- Deferred[F, Option[EventState[F, E, Option[A]]]]
                _ <- deferredMap.add(k)(d)
                es <- EventState[F].initial[E, A](v)
                _ <- d.complete(es.some)
              } yield k -> v
            }
          }
          .compile
          .lastOrError
          .map(_.toMap)

    }

    /** Create an EventStateCache backed by the supplied event log. States are kept in-memory for the specified duration
      * of time, and are then discarded. The next time a state is requested, it is restored from your event log.
      *
      * @param log
      *   Your event log you are restoring state from.
      * @param ttl
      *   The ttlation for states to remain in memory. Default is 2 minutes.
      * @param existenceCheck
      *   A function to quickly determine if a state exists or not before consulting the event log.
      * @return
      *   A new `EventStateCache` that loads states into memory and temporarily caches them.
      */
    def timed[K, E, A](
        log: KeyedFiniteEventLog[F, K, E, E],
        ttl: FiniteDuration = 2.minutes,
        existenceCheck: K => F[Boolean] = (_: K) => true.pure[F]
    )(implicit keyedState: KeyedState[K, E, A], F: Temporal[F]) = for {
      deferredMap <- DeferredMap[F].empty[K, Option[ExpiringRef[F, EventState[F, E, Option[A]]]]]
    } yield new EventStateCache[F, K, E, A] {
      private def notFound[A]: Either[EmptyState, A] = EmptyState.NotFound.asLeft
      private def removed[A]: Either[EmptyState, A] = EmptyState.Removed.asLeft
      private def useEventState[B](k: K)(f: EventState[F, E, Option[A]] => F[B]): F[Option[B]] = {
        val doTheThing: F[Option[B]] = deferredMap
          .getOrAddF(k) {
            log.getOneState[A](k).unNone.compile.last.flatMap {
              case Some(state) =>
                EventState[F].initial[E, A](state).flatMap { es =>
                  ExpiringRef[F]
                    .timed(es, ttl)
                    .flatTap(exp => Concurrent[F].start(exp.expired >> deferredMap.del(k)))
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
              none.pure[F]
          }

        existenceCheck(k).ifM(
          doTheThing,
          deferredMap.del(k).as(none)
        )
      }

      def use[B](k: K)(f: A => F[B]): F[Option[B]] = {
        useEventState(k)(_.get.flatMap(_.traverse(f))).flatMap {
          case Some(Some(v)) => v.some.pure[F]
          case _             => none.pure[F]
        }
      }

      def useDontCache[B](k: K)(f: A => F[B]): F[Option[B]] = {
        deferredMap.getOpt(k).flatMap {
          case Some(Some(exp)) => exp.use(_.get.flatMap(_.traverse(f))).map(_.flatten)
          case _ =>
            val getFromLog: F[Option[B]] = log.getOneState[A](k).unNone.compile.last.flatMap {
              case Some(v) => f(v).map(_.some)
              case None    => none.pure[F]
            }
            val del = deferredMap.del(k).as(none[B])
            existenceCheck(k).ifM(getFromLog, del)
        }
      }

      def addOnlyCached(e: E): F[Option[A]] =
        deferredMap
          .getOpt(e.getKey)
          .flatMap {
            case Some(Some(exp)) =>
              exp.use(es => log.add(e) >> es.doNext(e)).flatMap {
                case Some(Some(v)) => v.some.pure[F] //State exists in cache
                case Some(None)    => deferredMap.del(e.getKey).as(none) //Key was removed after applying event
                case None          => log.add(e).as(none) //State does not exist in cache
              }
            case Some(None) => log.add(e) >> deferredMap.del(e.getKey).as(none)
            case None       => log.add(e).as(none)
          }

      def addAndCache(e: E): F[Either[EmptyState, A]] =
        useEventState(e.getKey)(es => log.add(e) >> es.doNext(e)).flatMap {
          case Some(Some(v)) => v.asRight.pure[F] //State exists in cache
          case Some(None)    => deferredMap.del(e.getKey).as(removed) //Key was removed after applying event
          case None          =>
            //State does not exist in cache
            log.add(e).flatMap { _ =>
              None.handleEvent(e) match {
                case Some(v) =>
                  for {
                    d <- Deferred[F, Option[ExpiringRef[F, EventState[F, E, Option[A]]]]]
                    _ <- deferredMap.add(e.getKey)(d)
                    es <- EventState[F].initial[E, A](v)
                    expref <- ExpiringRef[F].timed(es, ttl)
                    _ <- d.complete(expref.some)
                  } yield v.asRight
                case None => notFound.pure[F]
              }
            }
        }

      def addAndRemove(e: E): F[Unit] = log.add(e) >> deferredMap.del(e.getKey).void

      def loadBatch(keys: NonEmptySet[K]): F[Map[K, A]] =
        log
          .getKeyedStateBatch[A](keys)
          .evalMap { m =>
            m.toList.traverse { case (k, v) =>
              for {
                d <- Deferred[F, Option[ExpiringRef[F, EventState[F, E, Option[A]]]]]
                _ <- deferredMap.add(k)(d)
                es <- EventState[F].initial[E, A](v)
                expref <- ExpiringRef[F].timed(es, ttl)
                _ <- d.complete(expref.some)
              } yield k -> v
            }
          }
          .compile
          .lastOrError
          .map(_.toMap)

    }
  }

  /** A set of constructors for entirely in-memory instances of `EventStateCache`. */
  def inMemory[F[_]: Concurrent] = new InMemoryEventStateCachePartiallyApplied[F]

  implicit def eventStateCacheProfunctor[F[_]: Functor, K]: Profunctor[EventStateCache[F, K, *, *]] =
    new Profunctor[EventStateCache[F, K, *, *]] {
      override def rmap[A, B, C](fab: EventStateCache[F, K, A, B])(f: B => C): EventStateCache[F, K, A, C] =
        new EventStateCache[F, K, A, C] {
          def use[D](k: K)(fun: C => F[D]): F[Option[D]] = fab.use(k)(b => fun(f(b)))
          def useDontCache[D](k: K)(fun: C => F[D]): F[Option[D]] = fab.useDontCache(k)(b => fun(f(b)))
          def addOnlyCached(e: A): F[Option[C]] = fab.addOnlyCached(e).map(_.map(f))
          def addAndCache(e: A): F[Either[EmptyState, C]] = fab.addAndCache(e).map(_.map(f))
          def addAndRemove(e: A): F[Unit] = fab.addAndRemove(e)
          def loadBatch(keys: NonEmptySet[K]): F[Map[K, C]] = fab.loadBatch(keys).map(_.view.mapValues(f).toMap)

        }
      override def lmap[A, B, C](fab: EventStateCache[F, K, A, B])(f: C => A): EventStateCache[F, K, C, B] =
        new EventStateCache[F, K, C, B] {
          def use[D](k: K)(fun: B => F[D]): F[Option[D]] = fab.use(k)(fun)
          def useDontCache[D](k: K)(fun: B => F[D]): F[Option[D]] = fab.useDontCache(k)(fun)
          def addOnlyCached(e: C): F[Option[B]] = fab.addOnlyCached(f(e))
          def addAndCache(e: C): F[Either[EmptyState, B]] = fab.addAndCache(f(e))
          def addAndRemove(e: C): F[Unit] = fab.addAndRemove(f(e))
          def loadBatch(keys: NonEmptySet[K]): F[Map[K, B]] = fab.loadBatch(keys)

        }
      def dimap[A, B, C, D](fab: EventStateCache[F, K, A, B])(f: C => A)(g: B => D): EventStateCache[F, K, C, D] =
        new EventStateCache[F, K, C, D] {
          def use[E](k: K)(fun: D => F[E]): F[Option[E]] = fab.use(k)(b => fun(g(b)))
          def useDontCache[E](k: K)(fun: D => F[E]): F[Option[E]] = fab.useDontCache(k)(b => fun(g(b)))
          def addOnlyCached(e: C): F[Option[D]] = fab.addOnlyCached(f(e)).map(_.map(g))
          def addAndCache(e: C): F[Either[EmptyState, D]] = fab.addAndCache(f(e)).map(_.map(g))
          def addAndRemove(e: C): F[Unit] = fab.addAndRemove(f(e))
          def loadBatch(keys: NonEmptySet[K]): F[Map[K, D]] = fab.loadBatch(keys).map(_.view.mapValues(g).toMap)
        }
    }
}
