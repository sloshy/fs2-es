package dev.rpeters.fs2.es

import fs2.Stream
import cats.Invariant

trait EventStateLogOps[F[_], S[_, _], E, A] {

  /** Attaches an `EventLog` so that when you `.doNext` on an event, it also gets passed to the underlying log. */
  def attachLog(s: S[E, A])(log: EventLog[F, E, _]): S[E, A]

  /** Attaches an `EventLog`, same as `attachLog`, but it also applies the event log to the current state first before returning it.
    * Result is a singleton stream containing your new state.
    */
  def attachLogAndApply(s: S[E, A])(log: EventLog[F, E, E]): Stream[F, S[E, A]]

  /** Similar to `EventLog#localizeInput`, this allows you to transform the expected input event type. */
  def localizeInput[EE](s: S[E, A])(f: EE => E): S[EE, A]

  /** Similar to `EventLog#mapOutput`, this allows you to transform the state result on output. */
  def mapState[AA](s: S[E, A])(f: A => AA): S[E, AA]
}

object EventStateLogOps {
  def apply[F[_], S[_, _], E, A](implicit instance: EventStateLogOps[F, S, E, A]) = instance
}
