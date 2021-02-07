package dev.rpeters.fs2.es

import cats.data.Chain
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.syntax.all._
import fs2.{Chunk, Stream}
import fs2.concurrent.Topic

/** A standard interface for accessing your event log. */
trait EventLog[F[_], E] {

  /** Add an event `E` to your event log. Should be appended to the tail of your log, and nowhere else.
    *
    * @param e The event to be added to your event log.
    * @return `F[Unit]` if nothing abnormal occurs, but may throw depending on the implementation.
    */
  def add(e: E): F[Unit]

  /** Stream all events from your event log.
    *
    * @return A stream of all events from your event log. May be repeatedly evaluated for updated results.
    */
  def stream: Stream[F, E]
}

object EventLog {

  /** Create an in-memory `EventLog`. */
  def inMemory[F[_]: Sync, E](initial: E) = inMemory[F, F, E](initial)

  /** Create an in-memory `EventLog` using two different effect types. */
  def inMemory[F[_]: Sync, G[_]: Sync, E](initial: E) = Ref.in[F, G, Chain[E]](Chain.nil).map { ref =>
    new EventLog[G, E] {
      def add(e: E): G[Unit] = ref.update(_ :+ e)
      def stream: Stream[G, E] =
        Stream.eval(ref.get).map(Chunk.chain).flatMap(Stream.chunk)
    }
  }
}
