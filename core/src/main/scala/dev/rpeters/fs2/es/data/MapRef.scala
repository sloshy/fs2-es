package dev.rpeters.fs2.es.data

import cats.implicits._
import cats.effect.concurrent.Ref
import cats.effect.Sync

/** A concurrent `Map` of values that you can modify without worrying about thread safety. */
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
  final class MapRefPartiallyApplied[F[_]: Sync, G[_]: Sync]() {
    private def mapFromRef[K, V](ref: Ref[G, Map[K, V]]) = new MapRef[G, K, V] {
      def add(kv: (K, V)): G[Unit] = ref.update(_ + kv)
      def del(k: K): G[Boolean] = ref.modify(m => m.get(k).fold(m -> false)(_ => m - k -> true))
      def get(k: K): G[Option[V]] = ref.get.map(_.get(k))
      def modify[A](k: K)(f: V => (V, A)): G[Option[A]] = ref.modify { map =>
        val resultOpt = map.get(k).map(f)
        resultOpt.fold(map -> Option.empty[A]) { result =>
          (map + (k -> result._1), result._2.some)
        }
      }
      def upsertOpt[A](k: K)(f: Option[V] => (V, A)): G[A] = ref.modify { map =>
        val (v, a) = f(map.get(k))
        (map + (k -> v), a)
      }
    }

    /** Construct an empty `MapRef`. */
    def empty[K, V] = Ref.in[F, G, Map[K, V]](Map.empty).map(mapFromRef)

    /** Construct a `MapRef` from a map of pure values. */
    def of[K, V](map: Map[K, V]) = Ref.in[F, G, Map[K, V]](map).map(mapFromRef)
  }

  /** A set of constructors for `MapRef` using the same effect type for everything. */
  def apply[F[_]: Sync] = new MapRefPartiallyApplied[F, F]

  /** A set of constructors for `MapRef` where you can use a different effect for your internal `MapRef`. */
  def in[F[_]: Sync, G[_]: Sync] = new MapRefPartiallyApplied[F, G]
}
