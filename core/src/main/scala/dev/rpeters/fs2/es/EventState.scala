package dev.rpeters.fs2.es

import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Ref
import fs2.{Pipe, Stream}
import fs2.concurrent.SignallingRef
import fs2.concurrent.Topic

/** An atomic reference that can only be modified through a linear application of events.
  * When you create one, all events are processed with the pre-supplied function you give it during construction.
  * Then, on every call to `doNext`, the state is atomically updated and returned to you.
  */
trait EventState[F[_], E, A] {

  /** Applies the given event to this `EventState` and returns the new resulting state. */
  def doNext(e: E): F[A]

  /** Gets the current value of state. */
  def get: F[A]

  /** Feeds a stream of events into this `EventState`, returning a new stream of all resulting states.
    * The resulting stream should be equivalent to a stream of all changes in state unless there are multiple hookups.
    * When in doubt, apply the Single Writer Principle and only use a single stream to apply updates unless this is not important.
    */
  def hookup: Pipe[F, E, A]

  /** The same as `hookup`, but also gives you the events passed through it as a tuple along with the resulting state. */
  def hookupWithInput: Pipe[F, E, (E, A)]
}

<<<<<<< HEAD
/** An `EventState` implementation that lets you `subscribe` to incoming events. */
trait EventStateTopic[F[_], E, A] extends EventState[F, E, A] {

  /** Get all emitted states from the moment of subscription.
    * Upon subscribing, you will receive the most current state and the event that generated it.
    */
  def subscribe: Stream[F, A]

  /** Pipe a series of events to this `EventState` and receive all updates.
    * This includes updates that are not from this hookup, and may be submitted elsewhere in your program.
    */
  def hookupAndSubscribe: Pipe[F, E, A]
}

/** An `EventState` implementation that lets you continuously monitor state changes.
  * If you are looking to get every single state change, look into `EventStateTopic` instead.
  * This is strictly for scenarios where you don't necessarily want every change, but want the latest changes regularly.
  */
sealed trait SignallingEventState[F[_], E, A] extends EventState[F, E, A] {
=======
trait SignallingEventState[F[_], E, A] extends EventState[F, E, A] {
>>>>>>> Unseal EventState and SignallingEventState

  /** A continuous stream of this state's current value at the time of pulling. */
  def continuous: Stream[F, A]

  /** A stream of the latest updates to state.
    * May not include all changes depending on when the current thread pulls.
    */
  def discrete: Stream[F, A]
}

object EventState {

  /** A function that takes an `event` and the current `state` and returns a new `state`.
    * Example: `val ef: EventProcessor[String, String] = (newS, currentS) => currentS ++ newS`
    */
  type EventProcessor[E, A] = (E, A) => A
  final class EventStatePartiallyApplied[F[_]: Sync]() {
    private def doNextInternal[E, A](e: E, ef: EventProcessor[E, A], state: Ref[F, A]) =
      state.modify { internalA =>
        val next = ef(e, internalA)
        next -> next
      }
    private def finalState[E, A](state: Ref[F, A], ef: EventProcessor[E, A]) =
      new EventState[F, E, A] {
        def doNext(e: E): F[A] = doNextInternal(e, ef, state)
        def get: F[A] = state.get
        def hookup: Pipe[F, E, A] = _.evalMap(doNext)
        def hookupWithInput: Pipe[F, E, (E, A)] = _.evalMap(e => doNext(e).tupleLeft(e))
      }

    /** Gives you an `EventState` that is initialized to a starting value. */
    def initial[E, A](a: A)(ef: EventProcessor[E, A]) =
      Ref[F].of(a).map { state =>
        finalState(state, ef)
      }

    /** Gives you an `EventState` that is restored from an existing stream of events.
      * The final `EventState` is returned upon completion of the hydrator stream.
      * Useful for rebuilding state from persisted or generated events, such as for a specific entity.
      */
    def hydrated[E, A](initial: A, hydrator: Stream[F, E])(ef: EventProcessor[E, A]) =
      Ref[F].of(initial).flatMap { state =>
        hydrator.evalTap(doNextInternal(_, ef, state)).compile.drain >> finalState(state, ef).pure[F]
      }

    /** Like `hydrated`, but returns the result as an FS2 `Stream`. */
    def hydratedStream[E, A](initial: A, hydrator: Stream[F, E])(ef: EventProcessor[E, A]) =
      Stream.eval(Ref[F].of(initial)).flatMap { state =>
        hydrator.evalTap(doNextInternal(_, ef, state)).last.as(finalState(state, ef))
      }
  }
  final class EventStateTopicPartiallyApplied[F[_]: Concurrent]() {
    private def doNextInternal[E, A](e: E, state: Ref[F, A], ef: EventProcessor[E, A]) =
      state.updateAndGet(ef(e, _))
    private def finalState[E, A](state: Ref[F, A], topic: Topic[F, A], ef: EventProcessor[E, A]) =
      new EventStateTopic[F, E, A] {
        def doNext(e: E): F[A] = doNextInternal(e, state, ef).flatMap(a => topic.publish1(a).as(a))

        def get: F[A] = state.get

        def hookup: Pipe[F, E, A] = _.evalMap(doNext)

        def hookupWithInput: Pipe[F, E, (E, A)] = _.evalMap(e => doNext(e).tupleLeft(e))

        def subscribe: Stream[F, A] = topic.subscribe(1)

        def hookupAndSubscribe: Pipe[F, E, A] = s => topic.subscribe(1).concurrently(s.through(hookup))

      }

    /** Gives you an `EventStateTopic` that is initialized to a starting value. */
    def initial[E, A](a: A)(ef: EventProcessor[E, A]) =
      for {
        state <- Ref[F].of(a)
        topic <- Topic[F, A](a)
      } yield finalState(state, topic, ef)

    /** Gives you an `EventStateTopic` that is restored from an existing stream of events.
      * The final `EventStateTopic` is returned upon completion of the hydrator stream.
      * Useful for rebuilding state from persisted or generated events, such as for a specific entity.
      */
    def hydrated[E, A](a: A, hydrator: Stream[F, E])(ef: EventProcessor[E, A]) =
      for {
        state <- Ref[F].of(a)
        _ <- hydrator.evalTap(doNextInternal(_, state, ef)).compile.drain
        topic <- state.get.flatMap(Topic.apply[F, A])
      } yield finalState(state, topic, ef)

    /** Like `hydrated`, but returns the result as an FS2 `Stream`. */
    def hydratedStream[E, A](a: A, hydrator: Stream[F, E])(ef: EventProcessor[E, A]) =
      for {
        state <- Stream.eval(Ref[F].of(a))
        _ <- hydrator.evalTap(doNextInternal(_, state, ef)).last
        topic <- Stream.eval(state.get.flatMap(Topic.apply[F, A]))
      } yield finalState(state, topic, ef)
  }
  final class SignallingEventStatePartiallyApplied[F[_]: Concurrent]() {
    private def doNextInternal[E, A](e: E, ef: EventProcessor[E, A], state: SignallingRef[F, A]) =
      state.modify { internalA =>
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
        def hookupWithInput: Pipe[F, E, (E, A)] = _.evalMap(e => doNext(e).tupleLeft(e))
      }

    /** Gives you an `EventState` that is initialized to a starting value. */
    def initial[E, A](a: A)(ef: EventProcessor[E, A]) =
      SignallingRef[F, A](a).map { state =>
        finalState(state, ef)
      }

    /** Gives you an `EventState` that is restored from an existing stream of events.
      * The final `EventState` is returned upon completion of the hydrator stream.
      * Useful for rebuilding state from persisted or generated events, such as for a specific entity.
      */
    def hydrated[E, A](initial: A, hydrator: Stream[F, E])(ef: EventProcessor[E, A]) =
      SignallingRef[F, A](initial).flatMap { state =>
        hydrator.evalTap(doNextInternal(_, ef, state)).compile.drain >> finalState(state, ef).pure[F]
      }

    /** Like `hydrated`, but returns the result as an FS2 `Stream` */
    def hydratedStream[E, A](initial: A, hydrator: Stream[F, E])(ef: EventProcessor[E, A]) =
      Stream.eval(SignallingRef[F, A](initial)).flatMap { state =>
        hydrator.evalTap(doNextInternal(_, ef, state)).last.as(finalState(state, ef))
      }
  }

  /** Selects the set of constructors for a base `EventState`.
    * Also see `topic` and `signalling`.
    */
  def apply[F[_]: Sync] = new EventStatePartiallyApplied[F]()

  /** Selects the set of constructors for an `EventStateTopic`, a variant of `EventState` that is subscribable. */
  def topic[F[_]: Concurrent] = new EventStateTopicPartiallyApplied[F]()

  /** Selects the set of constructors for a `SignallingEventState`, a variant of `EventState` that is continuously monitorable. */
  def signalling[F[_]: Concurrent] = new SignallingEventStatePartiallyApplied[F]()
}
