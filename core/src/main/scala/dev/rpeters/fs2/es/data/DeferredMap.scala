package dev.rpeters.fs2.es.data

import cats.Applicative
import cats.effect.concurrent.Deferred
import cats.effect._
import cats.effect.concurrent.TryableDeferred
import cats.implicits._

/** A map with values that may or may not be completed */
sealed trait DeferredMap[F[_], K, V, D <: Deferred[F, V]] {

  /** Add a value to this map that may be completed later. If this key is already being awaited by another Deferred, it
    * will attempt to complete the value silently.
    */
  def add(k: K)(d: D): F[Unit]

  /** Add a value to this map once a given `F` completes. If this key is already being awaited, it will attempt to
    * complete the value silently.
    */
  def addF(k: K)(f: F[V]): F[Unit]

  /** Add a pure value to this map that is pre-completed. If this key is being awaited, it will attempt to complete the
    * deferred upon insert silently.
    */
  def addPure(k: K)(v: V): F[Unit]

  /** Remove a value from this map. Result is whether or not the key was valid. */
  def del(k: K): F[Boolean]

  /** Get the value for a given key asynchronously once it is available. */
  def get(k: K): F[V]

  /** Get the value for a given key asynchronously only if the key currently exists. This means if a value is currently
    * being awaited, you will eventually receive `Some` value. Otherwise, it will immediately return `None`.
    */
  def getOpt(k: K): F[Option[V]]

  /** If the given key exists, await its final value. Otherwise, the provided deferred will be awaited and added to the
    * map immediately.
    */
  def getOrAdd(k: K)(d: D): F[V]

  /** If the given key exists, await its final value. Otherwise, the provided effect will be evaluated to obtain that
    * value. A Deferred is created internally so that the result can be awaited as it is evaluated.
    */
  def getOrAddF(k: K)(f: F[V]): F[V]

  /** If the given key exists, await its final value. Otherwise, the provided pure value `v` will be added to the map.
    */
  def getOrAddPure(k: K)(v: V): F[V]

  /** Get a `Deferred` that completes when the requested value is available. */
  def getDeferred(k: K): F[D]

  /** Like `getDeferred`, but if the given key does not exist the provided `Deferred` is added/returned. */
  def getDeferredOrAdd(k: K)(d: D): F[D]

  /** Get a `Deferred` that completes when the requested value is available if the given key currently exists. */
  def getDeferredOpt(k: K): F[Option[D]]

}

/** An extension of `DeferredMap` that supports checking the status of elements. */
sealed trait TryableDeferredMap[F[_], K, V] extends DeferredMap[F, K, V, TryableDeferred[F, V]] {

  /** Returns the current value only if the key exists and it has been completed */
  def tryGet(k: K): F[Option[V]]

  /** `None` if the key does not currently exist, `Some(true)` if completed, `Some(false)` if incomplete. */
  def checkCompleted(k: K): F[Option[Boolean]]

  /** Remove a value from this map only if it has been completed. Result is whether the operation is successful.
    */
  def delIfComplete(k: K): F[Option[Boolean]]

  /** Remove a value from this map only if its deferred has not been completed. Result is whether or not the operation
    * is successful.
    */
  def delIfIncomplete(k: K): F[Option[Boolean]]
}

object DeferredMap {
  final class DeferredMapPartiallyApplied[F[_]: Sync, G[_]: Concurrent] {
    private def construct[K, V](map: MapRef[G, K, Deferred[G, V]]) =
      new DeferredMap[G, K, V, Deferred[G, V]] {
        def add(k: K)(d: Deferred[G, V]): G[Unit] =
          map
            .upsertOpt(k) {
              case Some(existing) => existing -> d.get.flatMap(existing.complete).attempt.void
              case None           => d -> Applicative[G].unit
            }
            .flatten
        def addF(k: K)(f: G[V]): G[Unit] = Deferred[G, V].flatMap { d =>
          map
            .upsertOpt(k) {
              case Some(existing) => existing -> f.flatMap(existing.complete).attempt.void
              case None           => d -> f.flatMap(d.complete).attempt.void
            }
            .flatten
        }
        def addPure(k: K)(v: V): G[Unit] = Deferred[G, V].flatMap { d =>
          map
            .upsertOpt(k) {
              case Some(existing) => existing -> existing.complete(v).attempt.void
              case None           => d -> d.complete(v).attempt.void
            }
            .flatten
        }
        def del(k: K): G[Boolean] = map.del(k)
        def get(k: K): G[V] = Deferred[G, V].flatMap(getOrAdd(k))
        def getOpt(k: K): G[Option[V]] = getDeferredOpt(k).flatMap {
          case None    => Option.empty.pure[G]
          case Some(d) => d.get.map(_.some)
        }
        def getOrAdd(k: K)(d: Deferred[G, V]): G[V] = getDeferredOrAdd(k)(d).flatMap(_.get)
        def getOrAddF(k: K)(f: G[V]): G[V] = Deferred[G, V].flatMap { newDeferred =>
          map
            .upsertOpt(k) {
              case None    => (newDeferred, f.flatTap(newDeferred.complete))
              case Some(d) => (d, d.get)
            }
            .flatten
        }
        def getOrAddPure(k: K)(v: V): G[V] = Deferred[G, V].flatMap { newDeferred =>
          map
            .upsertOpt(k) {
              case None    => (newDeferred, v.pure[G].flatTap(newDeferred.complete))
              case Some(d) => (d, d.get)
            }
            .flatten
        }
        def getDeferred(k: K): G[Deferred[G, V]] = Deferred[G, V].flatMap(getDeferredOrAdd(k))
        def getDeferredOrAdd(k: K)(d: Deferred[G, V]): G[Deferred[G, V]] =
          map
            .upsertOpt(k) {
              case None        => (d, d)
              case Some(other) => (other, other)
            }
        def getDeferredOpt(k: K): G[Option[Deferred[G, V]]] = map.get(k)
      }

    private def constructTryable[K, V](map: MapRef[G, K, TryableDeferred[G, V]]) =
      new TryableDeferredMap[G, K, V] {
        def add(k: K)(d: TryableDeferred[G, V]): G[Unit] =
          map
            .upsertOpt(k) {
              case Some(existing) => existing -> d.get.flatMap(existing.complete).attempt.void
              case None           => d -> Applicative[G].unit
            }
            .flatten
        def addF(k: K)(f: G[V]): G[Unit] = Deferred.tryable[G, V].flatMap { d =>
          map
            .upsertOpt(k) {
              case Some(existing) => existing -> f.flatMap(existing.complete).attempt.void
              case None           => d -> f.flatMap(d.complete).attempt.void
            }
            .flatten
        }
        def addPure(k: K)(v: V): G[Unit] = Deferred.tryable[G, V] flatMap { d =>
          map
            .upsertOpt(k) {
              case Some(existing) => existing -> existing.complete(v).attempt.void
              case None           => d -> d.complete(v).attempt.void
            }
            .flatten
        }
        def del(k: K): G[Boolean] = map.del(k)
        def get(k: K): G[V] = Deferred.tryable[G, V].flatMap(getOrAdd(k))
        def getOpt(k: K): G[Option[V]] = getDeferredOpt(k).flatMap {
          case None    => Option.empty.pure[G]
          case Some(d) => d.get.map(_.some)
        }
        def getOrAdd(k: K)(d: TryableDeferred[G, V]): G[V] = getDeferredOrAdd(k)(d).flatMap(_.get)
        def getOrAddF(k: K)(f: G[V]): G[V] = Deferred.tryable[G, V].flatMap { newDeferred =>
          map
            .upsertOpt(k) {
              case None    => (newDeferred, f.flatTap(newDeferred.complete))
              case Some(d) => (d, d.get)
            }
            .flatten
        }
        def getOrAddPure(k: K)(v: V): G[V] = Deferred.tryable[G, V].flatMap { newDeferred =>
          map
            .upsertOpt(k) {
              case None    => (newDeferred, v.pure[G].flatTap(newDeferred.complete))
              case Some(d) => (d, d.get)
            }
            .flatten
        }
        def getDeferred(k: K): G[TryableDeferred[G, V]] = Deferred.tryable[G, V].flatMap(getDeferredOrAdd(k))
        def getDeferredOrAdd(k: K)(d: TryableDeferred[G, V]): G[TryableDeferred[G, V]] =
          map
            .upsertOpt(k) {
              case None        => (d, d)
              case Some(other) => (other, other)
            }
        def getDeferredOpt(k: K): G[Option[TryableDeferred[G, V]]] = map.get(k)
        def tryGet(k: K): G[Option[V]] = map.get(k).flatMap {
          case None    => Option.empty.pure[G]
          case Some(d) => d.tryGet
        }
        def checkCompleted(k: K): G[Option[Boolean]] = map.get(k).flatMap {
          case None => Option.empty.pure[G]
          case Some(d) =>
            d.tryGet.flatMap {
              case None    => false.some.pure[G]
              case Some(_) => true.some.pure[G]
            }
        }
        def delIfComplete(k: K): G[Option[Boolean]] = checkCompleted(k).flatMap {
          case None        => Option.empty.pure[G]
          case Some(true)  => map.del(k).map(_.some)
          case Some(false) => false.some.pure[G]
        }
        def delIfIncomplete(k: K): G[Option[Boolean]] = checkCompleted(k).flatMap {
          case None        => Option.empty.pure[G]
          case Some(true)  => false.some.pure[G]
          case Some(false) => map.del(k).map(_.some)
        }
      }

    /** Construct an empty `DeferredMap`. */
    def empty[K, V] = MapRef.in[F, G].empty[K, Deferred[G, V]].map(construct)

    /** Construct a `DeferredMap` from an existing map of `Deferred` values. */
    def of[K, V](map: Map[K, Deferred[G, V]]) = MapRef.in[F, G].of(map).map(construct)

    /** Construct an empty `TryableDeferredMap`. */
    def tryableEmpty[K, V] = MapRef.in[F, G].empty[K, TryableDeferred[G, V]].map(constructTryable[K, V])

    /** Construct a `TryableDeferredMap` from an existing map of `TryableDeferred` values. */
    def tryableOf[K, V](map: Map[K, TryableDeferred[G, V]]) = MapRef.in[F, G].of(map).map(constructTryable[K, V])
  }

  /** A set of constructors for `DeferredMap` using the same effect type for everything. */
  def apply[F[_]: Concurrent] = new DeferredMapPartiallyApplied[F, F]

  /** A set of constructors for `DeferredMap` where you can use a different effect for your internal `DeferredMap`. */
  def in[F[_]: Sync, G[_]: Concurrent] = new DeferredMapPartiallyApplied[F, G]
}
