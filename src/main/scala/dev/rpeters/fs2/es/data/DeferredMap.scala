package dev.rpeters.fs2.es.data

import cats.implicits._
import cats.effect.concurrent.Deferred
import cats.effect._
import cats.effect.concurrent.TryableDeferred

import scala.concurrent.duration._

/** A map with values that may or may not be completed */
sealed trait DeferredMap[F[_], K, V, D <: Deferred[F, V]] {

  /** Add a value to this map that may be completed later. */
  def add(k: K)(d: D): F[Unit]

  /** Add a value to this map once a given `F` completes. */
  def addF(k: K)(f: F[V]): F[Unit]

  /** Add a pure value to this map that is pre-completed. */
  def addPure(k: K)(v: V): F[Unit]

  /** Remove a value from this map. Result is whether or not the key was valid. */
  def del(k: K): F[Boolean]

  /** Get the value for a given key asynchronously once it is available. */
  def get(k: K): F[V]

  /** Get the value for a given key asynchronously only if the key currently exists.
    * This means if a value is currently being awaited, you will eventually receive `Some` value.
    * Otherwise, it will immediately return `None`. */
  def getOpt(k: K): F[Option[V]]

  /** If the given key exists, await its final value.
    * Otherwise, the provided deferred will be awaited and added to the map immediately. */
  def getOrAdd(k: K)(d: D): F[V]

  /** If the given key exists, await its final value.
    * Otherwise, the provided effect will be evaluated to obtain that value.
    * A Deferred is created internally so that the result can be awaited as it is evaluated. */
  def getOrAddF(k: K)(f: F[V]): F[V]

  /** If the given key exists, await its final value.
    * Otherwise, the provided pure value `v` will be added to the map. */
  def getOrAddPure(k: K)(v: V): F[V]

  /** Get a `Deferred` that completes when the requested value is available. */
  def getDeferred(k: K): F[D]

  /** Get a `Deferred` that completes when the requested value is available if the given key currently exists. */
  def getDeferredOpt(k: K): F[Option[D]]

}

/** An extension of `DeferredMap` that supports checking the status of elements. */
sealed trait TryableDeferredMap[F[_], K, V] extends DeferredMap[F, K, V, TryableDeferred[F, V]] {

  /** None if the key does not exist, true if the value is completed, false if not. */
  def tryGet(k: K): F[Option[V]]

  /** Remove a value from this map only if it has been completed.
    * Result is whether the operation is successful. */
  def delIfComplete(k: K): F[Option[Boolean]]

  /** Remove a value from this map only if its deferred has not been completed.
    * Result is whether or not the operation is successful. */
  def delIfIncomplete(k: K): F[Option[Boolean]]
}

object DeferredMap {
  final class DeferredMapPartiallyApplied[F[_]: Concurrent: Timer] {
    private def construct[K, V](map: MapRef[F, K, Deferred[F, V]]) =
      new DeferredMap[F, K, V, Deferred[F, V]] {
        def add(k: K)(d: Deferred[F, V]): F[Unit] = map.add(k -> d)
        def addF(k: K)(f: F[V]): F[Unit] = Deferred[F, V].flatMap { d =>
          map.add(k -> d) >> f.flatMap(d.complete)
        }
        def addPure(k: K)(v: V): F[Unit] = Deferred[F, V].flatMap { d =>
          map.add(k -> d) >> d.complete(v)
        }
        def del(k: K): F[Boolean] = map.del(k)
        def get(k: K): F[V] = getDeferred(k).flatMap(_.get)
        def getOpt(k: K): F[Option[V]] = getDeferredOpt(k).flatMap {
          case None    => Option.empty.pure[F]
          case Some(d) => d.get.map(_.some)
        }
        def getOrAdd(k: K)(d: Deferred[F, V]): F[V] =
          map
            .upsertOpt(k) {
              case None        => (d, d.get)
              case Some(other) => (other, other.get)
            }
            .flatten
        def getOrAddF(k: K)(f: F[V]): F[V] = Deferred[F, V].flatMap { newDeferred =>
          map
            .upsertOpt(k) {
              case None    => (newDeferred, f.flatTap(newDeferred.complete))
              case Some(d) => (d, d.get)
            }
            .flatten
        }
        def getOrAddPure(k: K)(v: V): F[V] = Deferred[F, V].flatMap { newDeferred =>
          map
            .upsertOpt(k) {
              case None    => (newDeferred, v.pure[F].flatTap(newDeferred.complete))
              case Some(d) => (d, d.get)
            }
            .flatten
        }
        def getDeferred(k: K): F[Deferred[F, V]] = getDeferredOpt(k).flatMap {
          case Some(d) => d.pure[F]
          case None    => Timer[F].sleep(0.seconds) >> getDeferred(k)
        }
        def getDeferredOpt(k: K): F[Option[Deferred[F, V]]] = map.get(k)
      }

    private def constructTryable[K, V](map: MapRef[F, K, TryableDeferred[F, V]]) =
      new TryableDeferredMap[F, K, V] {
        def add(k: K)(d: TryableDeferred[F, V]): F[Unit] = map.add(k -> d)
        def addF(k: K)(f: F[V]): F[Unit] = Deferred.tryable[F, V].flatMap { d =>
          map.add(k -> d) >> f.flatMap(d.complete)
        }
        def addPure(k: K)(v: V): F[Unit] = Deferred.tryable[F, V].flatMap { d =>
          map.add(k -> d) >> d.complete(v)
        }
        def del(k: K): F[Boolean] = map.del(k)
        def get(k: K): F[V] = getDeferred(k).flatMap(_.get)
        def getOpt(k: K): F[Option[V]] = getDeferredOpt(k).flatMap {
          case None    => Option.empty.pure[F]
          case Some(d) => d.get.map(_.some)
        }
        def getOrAdd(k: K)(d: TryableDeferred[F, V]): F[V] =
          map
            .upsertOpt(k) {
              case None        => (d, d.get)
              case Some(other) => (other, other.get)
            }
            .flatten
        def getOrAddF(k: K)(f: F[V]): F[V] = Deferred.tryable[F, V].flatMap { newDeferred =>
          map
            .upsertOpt(k) {
              case None    => (newDeferred, f.flatTap(newDeferred.complete))
              case Some(d) => (d, d.get)
            }
            .flatten
        }
        def getOrAddPure(k: K)(v: V): F[V] = Deferred.tryable[F, V].flatMap { newDeferred =>
          map
            .upsertOpt(k) {
              case None    => (newDeferred, v.pure[F].flatTap(newDeferred.complete))
              case Some(d) => (d, d.get)
            }
            .flatten
        }
        def delIfComplete(k: K): F[Option[Boolean]] = tryGet(k).flatMap {
          case None => Option.empty.pure[F]
          case _    => map.del(k).map(_.some)
        }
        def delIfIncomplete(k: K): F[Option[Boolean]] = delIfComplete(k).map(_.map(bool => !bool))
        def getDeferred(k: K): F[TryableDeferred[F, V]] = getDeferredOpt(k).flatMap {
          case Some(d) => d.pure[F]
          case None    => Timer[F].sleep(0.seconds) >> getDeferred(k)
        }
        def getDeferredOpt(k: K): F[Option[TryableDeferred[F, V]]] = map.get(k)
        def tryGet(k: K): F[Option[V]] = map.get(k).flatMap {
          case None    => Option.empty.pure[F]
          case Some(d) => d.tryGet
        }
      }

    /** Construct an empty DeferredMap */
    def empty[K, V] = MapRef[F].empty[K, Deferred[F, V]].map(construct)

    /** */
    def of[K, V](map: Map[K, Deferred[F, V]]) = MapRef[F].of(map).map(construct)
    def ofPure[K, V](map: Map[K, V]) = {
      val newKvs = map.toList.traverse {
        case (k, v) =>
          Deferred[F, V].flatMap(d => d.complete(v).as(k -> d))
      }
      newKvs.flatMap(x => MapRef[F].of(x.toMap).map(construct))
    }
    def tryableEmpty[K, V] = MapRef[F].empty[K, TryableDeferred[F, V]].map(constructTryable[K, V])
    def tryableOf[K, V](map: Map[K, TryableDeferred[F, V]]) = MapRef[F].of(map).map(constructTryable[K, V])
    def tryablePure[K, V](map: Map[K, V]) = {
      val newKvs = map.toList.traverse {
        case (k, v) =>
          Deferred.tryable[F, V].flatMap(d => d.complete(v).as(k -> d))
      }
      newKvs.flatMap(x => MapRef[F].of(x.toMap).map(constructTryable))
    }
  }
  def apply[F[_]: Concurrent: Timer] = new DeferredMapPartiallyApplied[F]
}
