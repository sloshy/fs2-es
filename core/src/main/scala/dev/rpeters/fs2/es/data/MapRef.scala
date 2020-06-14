package dev.rpeters.fs2.es.data

import cats.implicits._
import cats.effect.concurrent.Ref
import cats.effect.Sync

sealed trait MapRef[F[_], K, V] {

  /** Add a key/value pair to the map. Overwrites a value if it exists. */
  def add(kv: (K, V)): F[Unit]

  /** Delete a key from the map if it exists. Result is whether the key existed in the map. */
  def del(k: K): F[Boolean]

  /** Get a value if it exists from the map. */
  def get(k: K): F[Option[V]]

  /** Atomically modify the value of an entry by key, returning a result value. */
  def modify[A](k: K)(f: V => (V, A)): F[Option[A]]

  /** Atomically add or modify the value for a given key. */
  def upsertOpt[A](k: K)(f: Option[V] => (V, A)): F[A]
}

object MapRef {
  final class MapRefPartiallyApplied[F[_]: Sync]() {
    private def mapFromRef[K, V](ref: Ref[F, Map[K, V]]) = new MapRef[F, K, V] {
      def add(kv: (K, V)): F[Unit] = ref.update(_ + kv)
      def del(k: K): F[Boolean] = ref.modify(m => m.get(k).fold(m -> false)(_ => m - k -> true))
      def get(k: K): F[Option[V]] = ref.get.map(_.get(k))
      def modify[A](k: K)(f: V => (V, A)): F[Option[A]] = ref.modify { map =>
        val resultOpt = map.get(k).map(f)
        resultOpt.fold(map -> Option.empty[A]) { result =>
          (map + (k -> result._1), result._2.some)
        }
      }
      def upsertOpt[A](k: K)(f: Option[V] => (V, A)): F[A] = ref.modify { map =>
        val (v, a) = f(map.get(k))
        (map + (k -> v), a)
      }
    }
    def empty[K, V] = Ref[F].of(Map.empty[K, V]).map(mapFromRef)
    def of[K, V](map: Map[K, V]) = Ref[F].of(map).map(mapFromRef)
  }
  def apply[F[_]: Sync] = new MapRefPartiallyApplied[F]
}
