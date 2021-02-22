package dev.rpeters.fs2.es.data

import cats.implicits._
import cats.effect.kernel.{Concurrent, Ref}

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
  final class MapRefPartiallyApplied[F[_]: Concurrent]() {
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

    /** Construct an empty `MapRef`. */
    def empty[K, V] = Ref.of[F, Map[K, V]](Map.empty).map(mapFromRef)

    /** Construct a `MapRef` from a map of pure values. */
    def of[K, V](map: Map[K, V]) = Ref.of[F, Map[K, V]](map).map(mapFromRef)
  }

  /** A set of constructors for `MapRef`. */
  def apply[F[_]: Concurrent] = new MapRefPartiallyApplied[F]
}
