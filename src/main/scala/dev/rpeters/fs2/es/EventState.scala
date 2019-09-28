package dev.rpeters.fs2.es

import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Ref
import fs2.{Pipe, Stream}
import fs2.concurrent.SignallingRef

sealed trait EventState[F[_], E, A] {

  /** Applies the given event to this `EventState` and returns the new resulting state. */
  def doNext(e: E): F[A]

  /** Gets the current value of state. */
  def get: F[A]

  /** Feeds a stream of events into this `EventState`, returning a new stream of all resulting states.
    * The resulting stream should be equivalent to a stream of all changes in state unless there are multiple hookups.
    * When in doubt, apply the Single Writer Principle and only use a single stream to apply updates unless this is not important. */
  def hookup: Pipe[F, E, A]
}

sealed trait SignallingEventState[F[_], E, A] extends EventState[F, E, A] {

  /** A continuous stream of this state's current value.
    * See [[SignallingRef.continuous]]. */
  def continuous: Stream[F, A]

  /** A stream of the latest updates to state.
    * May not include all changes depending on when the current thread pulls.
    * See [[SignallingRef.discrete]]. */
  def discrete: Stream[F, A]
}

object EventState {

  /** A function that takes an `event` and the current `state` and returns a new `state`.
    * Example: `val ef: EventProcessor[String, String] = (newS, currentS) => currentS ++ newS` */
  type EventProcessor[E, A] = (E, A) => A
  final class EventStatePartiallyApplied[F[_]: Sync]() {
    private def doNextInternal[E, A](e: E, ef: EventProcessor[E, A], state: Ref[F, A]) = state.modify { internalA =>
      val next = ef(e, internalA)
      next -> next
    }
    private def finalState[E, A](state: Ref[F, A], ef: EventProcessor[E, A]) = new EventState[F, E, A] {
      def doNext(e: E): F[A] = doNextInternal(e, ef, state)
      def get: F[A] = state.get
      def hookup: Pipe[F, E, A] = _.evalMap(doNext)
    }

    /** Gives you an `EventState` that is initialized to a starting value. */
    def initial[E, A](a: A)(ef: EventProcessor[E, A]) = Ref[F].of(a).map { state =>
      finalState(state, ef)
    }

    /** Gives you an `EventState` that is restored from an existing stream of events.
      * The final `EventState` is returned upon completion of the hydrator stream.
      * Useful for rebuilding state from persisted or generated events, such as for a specific entity. */
    def hydrated[E, A](initial: A, hydrator: Stream[F, E])(ef: EventProcessor[E, A]) = Ref[F].of(initial).flatMap {
      state =>
        hydrator.evalTap(doNextInternal(_, ef, state)).compile.drain >> finalState(state, ef).pure[F]
    }
  }
  final class SignallingEventStatePartiallyApplied[F[_]: Concurrent]() {
    private def doNextInternal[E, A](e: E, ef: EventProcessor[E, A], state: SignallingRef[F, A]) = state.modify {
      internalA =>
        val next = ef(e, internalA)
        next -> next
    }
    private def finalState[E, A](state: SignallingRef[F, A], ef: EventProcessor[E, A]) =
      new SignallingEventState[F, E, A] {
        def doNext(e: E): F[A] = doNextInternal(e, ef, state)
        def get: F[A] = state.get
        def continuous: Stream[F, A] = state.continuous
        def discrete: Stream[F, A] = state.discrete
        def hookup: Pipe[F, E, A] = _.evalMap(doNext)
      }

    /** Gives you an `EventState` that is initialized to a starting value. */
    def initial[E, A](a: A)(ef: EventProcessor[E, A]) = SignallingRef[F, A](a).map { state =>
      finalState(state, ef)
    }

    /** Gives you an `EventState` that is restored from an existing stream of events.
      * The final `EventState` is returned upon completion of the hydrator stream.
      * Useful for rebuilding state from persisted or generated events, such as for a specific entity. */
    def hydrated[E, A](initial: A, hydrator: Stream[F, E])(ef: EventProcessor[E, A]) =
      SignallingRef[F, A](initial).flatMap { state =>
        hydrator.evalTap(doNextInternal(_, ef, state)).compile.drain >> finalState(state, ef).pure[F]
      }
  }
  def apply[F[_]: Sync] = new EventStatePartiallyApplied[F]()
  def signalling[F[_]: Concurrent] = new SignallingEventStatePartiallyApplied[F]()
}
