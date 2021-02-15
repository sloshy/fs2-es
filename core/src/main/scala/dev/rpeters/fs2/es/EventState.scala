package dev.rpeters.fs2.es

import cats.Functor
import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.Ref
import cats.syntax.all._
import fs2.{Pipe, Stream}
import fs2.concurrent.SignallingRef
import fs2.concurrent.Topic
import syntax._

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
  def hookup: Pipe[F, E, A] = _.evalMap(doNext)

  /** The same as `hookup`, but also gives you the events passed through it as a tuple along with the resulting state. */
  def hookupWithInput(implicit F: Functor[F]): Pipe[F, E, (E, A)] = _.evalMap(e => doNext(e).tupleLeft(e))

  /** Feeds a stream of events into this `EventState` and returns the final state.
    * If you don't have a `Sync` constraint, you are probably better off using `doNextStream` or `doNext` directly.
    */
  def doNext(eventStream: Stream[F, E])(implicit F: Sync[F]): F[A] =
    eventStream.through(hookup).compile.drain >> get

  /** Feeds a stream of events into this `EventState` and returns the final state as a singleton stream. */
  def doNextStream(eventStream: Stream[F, E]): Stream[F, A] = eventStream.through(hookup).drain ++ Stream.eval(get)
}

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
trait SignallingEventState[F[_], E, A] extends EventState[F, E, A] {

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
  final class EventStatePartiallyApplied[F[_]: Sync, G[_]: Sync]() {
    private def doNextInternal[E, A](e: E, ef: EventProcessor[E, A], state: Ref[G, A]) =
      state.modify { internalA =>
        val next = ef(e, internalA)
        next -> next
      }
    private def finalState[E, A](state: Ref[G, A], ef: EventProcessor[E, A]) =
      new EventState[G, E, A] {
        def doNext(e: E): G[A] = doNextInternal(e, ef, state)
        def get: G[A] = state.get
      }

    /** Gives you an `EventState` that is initialized to a starting value. */
    def initial[E, A](a: A)(implicit ev: Driven[E, A]): F[EventState[G, E, Option[A]]] =
      for {
        state <- Ref.in[F, G, Option[A]](a.some)
      } yield finalState(state, (event, state) => state.flatMap(_.handleEvent(event)))

    /** Gives you an `EventState` that is initialized to a starting value and cannot be deleted.
      * In the event that an event would otherwise "delete" your state, it keeps the current state value.
      */
    def total[E, A](a: A)(implicit ev: DrivenNonEmpty[E, A]): F[EventState[G, E, A]] =
      for {
        state <- Ref.in[F, G, A](a)
      } yield finalState(state, (event, state) => state.handleEventOrDefault(event))

    /** Gives you an `EventState` that is not yet initialized. */
    def empty[E, A](implicit ev: Driven[E, A]): F[EventState[G, E, Option[A]]] =
      for {
        state <- Ref.in[F, G, Option[A]](none)
      } yield finalState(state, (event, state) => state.handleEvent(event))
  }
  final class EventStateTopicPartiallyApplied[F[_]: Sync, G[_]: Concurrent]() {
    private def doNextInternal[E, A](e: E, state: Ref[G, A], ef: EventProcessor[E, A]) =
      state.updateAndGet(ef(e, _))
    private def finalState[E, A](state: Ref[G, A], topic: Topic[G, A], ef: EventProcessor[E, A]) =
      new EventStateTopic[G, E, A] {
        def doNext(e: E): G[A] = doNextInternal(e, state, ef).flatMap(a => topic.publish1(a).as(a))
        def get: G[A] = state.get
        def subscribe: Stream[G, A] = topic.subscribe(1)
        def hookupAndSubscribe: Pipe[G, E, A] = s => topic.subscribe(1).concurrently(s.through(hookup))
      }

    /** Gives you an `EventStateTopic` that is initialized to a starting value. */
    def initial[E, A](a: A)(implicit ev: Driven[E, A]): F[EventStateTopic[G, E, Option[A]]] =
      for {
        state <- Ref.in[F, G, Option[A]](a.some)
        topic <- Topic.in[F, G, Option[A]](a.some)
      } yield finalState(state, topic, (event, state) => state.flatMap(_.handleEvent(event)))

    /** Gives you an `EventStateTopic` that is initialized to a starting value and cannot be deleted.
      * In the event that an event would otherwise "delete" your state, it keeps the current state value.
      */
    def total[E, A](a: A)(implicit ev: DrivenNonEmpty[E, A]): F[EventStateTopic[G, E, A]] =
      for {
        state <- Ref.in[F, G, A](a)
        topic <- Topic.in[F, G, A](a)
      } yield finalState(state, topic, (event, state) => state.handleEventOrDefault(event))

    /** Gives you an `EventStateTopic` that is not yet initialized. */
    def empty[E, A](implicit ev: Driven[E, A]): F[EventStateTopic[G, E, Option[A]]] =
      for {
        state <- Ref.in[F, G, Option[A]](none)
        topic <- Topic.in[F, G, Option[A]](none)
      } yield finalState(state, topic, (event, state) => state.handleEvent(event))
  }
  final class SignallingEventStatePartiallyApplied[F[_]: Sync, G[_]: Concurrent]() {
    private def doNextInternal[E, A](e: E, ef: EventProcessor[E, A], state: SignallingRef[G, A]) =
      state.modify { internalA =>
        val next = ef(e, internalA)
        next -> next
      }
    private def finalState[E, A](state: SignallingRef[G, A], ef: EventProcessor[E, A]) =
      new SignallingEventState[G, E, A] {
        def doNext(e: E): G[A] = doNextInternal(e, ef, state)
        def get: G[A] = state.get
        def continuous: Stream[G, A] = state.continuous
        def discrete: Stream[G, A] = state.discrete
      }

    /** Gives you a `SignallingEventState` that is initialized to a starting value. */
    def initial[E, A](a: A)(implicit ev: Driven[E, A]): F[SignallingEventState[G, E, Option[A]]] =
      for {
        state <- SignallingRef.in[F, G, Option[A]](a.some)
      } yield finalState(state, (event, state) => state.flatMap(_.handleEvent(event)))

    /** Gives you a `SignallingEventState` that is initialized to a starting value and cannot be deleted.
      * In the event that an event would otherwise "delete" your state, it keeps the current state value.
      */
    def total[E, A](a: A)(implicit ev: DrivenNonEmpty[E, A]): F[SignallingEventState[G, E, A]] =
      for {
        state <- SignallingRef.in[F, G, A](a)
      } yield finalState(state, (event, state) => state.handleEventOrDefault(event))

    /** Gives you a `SignallingEventState` that is not yet initialized. */
    def empty[E, A](implicit ev: Driven[E, A]): F[SignallingEventState[G, E, Option[A]]] =
      for {
        state <- SignallingRef.in[F, G, Option[A]](none)
      } yield finalState(state, (event, state) => state.handleEvent(event))
  }

  /** Selects the set of constructors for a base `EventState`.
    * Also see `topic` and `signalling`.
    */
  def apply[F[_]: Sync] = new EventStatePartiallyApplied[F, F]()

  def in[F[_]: Sync, G[_]: Sync] = new EventStatePartiallyApplied[F, G]()

  /** Selects the set of constructors for an `EventStateTopic`, a variant of `EventState` that is subscribable. */
  def topic[F[_]: Concurrent] = new EventStateTopicPartiallyApplied[F, F]()

  def topicIn[F[_]: Sync, G[_]: Concurrent] = new EventStateTopicPartiallyApplied[F, G]()

  /** Selects the set of constructors for a `SignallingEventState`, a variant of `EventState` that is continuously monitorable. */
  def signalling[F[_]: Concurrent] = new SignallingEventStatePartiallyApplied[F, F]()

  def signallingIn[F[_]: Sync, G[_]: Concurrent] = new SignallingEventStatePartiallyApplied[F, G]()
}
