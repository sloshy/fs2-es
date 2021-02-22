package dev.rpeters.fs2.es.data

import cats.Applicative
import cats.kernel.Eq
import cats.effect.kernel.{Concurrent, Ref}
import cats.syntax.all._
import scala.collection.immutable.Queue
import scala.annotation.tailrec

trait LRU[F[_], A] {
  def use(a: A): F[Int]
  def pop: F[Option[A]]
  def del(a: A): F[Int]
  def dump: F[Queue[A]]
}

object LRU {
  def apply[F[_]: Concurrent, A] = Ref[F].of[(Queue[A], Set[A])](Queue.empty -> Set.empty).map { ref =>
    new LRU[F, A] {
      private def filterFirst(q: Queue[A])(pred: A => Boolean) = {
        val it = q.iterator

        @tailrec
        def go(o: Option[A], acc: Queue[A]): Queue[A] = o match {
          case Some(next) =>
            if (pred(next)) {
              acc ++ Queue.from(it)
            } else {
              go(it.nextOption, acc :+ next)
            }
          case None => acc
        }

        go(it.nextOption, Queue.empty)
      }
      def use(a: A): F[Int] = ref.modify { case (q, s) =>
        if (s.contains(a)) {
          val resQ = filterFirst(q)(_ == a).enqueue(a)
          (resQ -> s) -> s.size
        } else {
          val resQ = q.enqueue(a)
          val resS = s + a
          (resQ -> resS) -> resS.size
        }
      }
      def pop: F[Option[A]] = ref.modify { case (q, s) =>
        q.dequeueOption
          .map { case (a, tail) =>
            val resS = s - a
            (tail -> resS) -> a.some
          }
          .getOrElse((q -> s) -> none)
      }
      def del(a: A): F[Int] = ref.modify { case (q, s) =>
        if (s.contains(a)) {
          val resQ = filterFirst(q)(_ == a)
          val resS = s - a
          (resQ -> resS) -> resS.size
        } else {
          (q -> s) -> s.size
        }
      }
      def dump: F[Queue[A]] = ref.get.map(_._1)
    }
  }
}
