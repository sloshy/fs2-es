package dev.rpeters.fs2.es

import fs2.Stream
import cats.Invariant

trait EventStateLogOps[F[_], S[_, _], E, A] {

  /** Attaches an `EventLogSink` so that when you `.doNext` on an event, it also gets passed to the underlying log. */
  def attachLog(s: S[E, A])(log: EventLogSink[F, E]): S[E, A]

  /** Attaches a `FiniteEventLog` as an `EventLogSink`, but it also applies the entire event log to the current state first before returning it.
    * Result is a singleton stream containing your new state.
    */
  def attachLogAndApply(s: S[E, A])(log: FiniteEventLog[F, E, E]): Stream[F, S[E, A]]
}

object EventStateLogOps {
  def apply[F[_], S[_, _], E, A](implicit instance: EventStateLogOps[F, S, E, A]) = instance
}
