package dev.rpeters.fs2.es.data

import cats.effect.Sync
import cats.kernel.Eq
import cats.effect.concurrent.Ref
import cats.syntax.all._
import scala.collection.immutable.Queue
import cats.Applicative
import scala.annotation.tailrec

trait LRU[F[_], A] {
  def use(a: A): F[Int]
  def pop: F[Int]
  def del(a: A): F[Int]
}

object LRU {
  def apply[F[_]: Sync, A] = in[F, F, A]
  def in[F[_]: Sync, G[_]: Sync, A] = Ref.in[F, G, (Queue[A], Set[A])](Queue.empty -> Set.empty).map { ref =>
    new LRU[G, A] {
      private def filterFirst(q: Queue[A])(pred: A => Boolean) = {
        val it = q.iterator

        @tailrec
        def go(o: Option[A], acc: Queue[A]): Queue[A] = o match {
          case Some(next) =>
            if (pred(next)) {
              acc ++ it.to(Queue)
            }
            go(it.nextOption, acc :+ next)
          case None => acc
        }

        go(it.nextOption, Queue.empty)
      }
      def use(a: A): G[Int] = ref.modify { case (q, s) =>
        if (s.contains(a)) {
          val resQ = filterFirst(q)(_ == a).enqueue(a)
          (resQ -> s) -> s.size
        } else {
          val resQ = q.enqueue(a)
          val resS = s + a
          (resQ -> resS) -> resS.size
        }
      }
      def pop: G[Int] = ref.modify { case (q, s) =>
        q.dequeueOption
          .map { case (a, tail) =>
            val resS = s - a
            val size = resS.size
            (tail -> resS) -> size
          }
          .getOrElse((q -> s) -> s.size)
      }
      def del(a: A): G[Int] = ref.modify { case (q, s) =>
        if (s.contains(a)) {
          val resQ = filterFirst(q)(_ == a)
          val resS = s - a
          (resQ -> resS) -> resS.size
        } else {
          (q -> s) -> s.size
        }
      }
    }
  }
  private[es] def stub[F[_]: Applicative, A] = new LRU[F, A] {
    def use(a: A): F[Int] = 0.pure[F]
    def pop: F[Int] = 0.pure[F]
    def del(a: A): F[Int] = 0.pure[F]
  }
}
