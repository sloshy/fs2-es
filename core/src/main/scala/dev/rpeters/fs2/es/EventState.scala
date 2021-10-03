package dev.rpeters.fs2.es

import cats.{Applicative, Functor}
import cats.arrow.Profunctor
import cats.effect.kernel.{Concurrent, Ref}
import cats.syntax.all._
import fs2.{Pipe, Stream}
import fs2.concurrent.{SignallingRef, Topic}
import syntax._

/** An atomic reference that can only be modified through a linear application of events. When you create one, all
  * events are processed with the pre-supplied function you give it during construction. Then, on every call to
  * `doNext`, the state is atomically updated and returned to you.
  */
trait EventState[F[_], E, A] {

  /** Applies the given event to this `EventState` and returns the new resulting state. */
  def doNext(e: E): F[A]

  /** Gets the current value of state. */
  def get: F[A]

  /** Feeds a stream of events into this `EventState`, returning a new stream of all resulting states. The resulting
    * stream should be equivalent to a stream of all changes in state unless there are multiple hookups. When in doubt,
    * apply the Single Writer Principle and only use a single stream to apply updates unless this is not important.
    */
  def hookup: Pipe[F, E, A] = _.evalMap(doNext)

  /** The same as `hookup`, but also gives you the events passed through it as a tuple along with the resulting state.
    */
  def hookupWithInput(implicit F: Functor[F]): Pipe[F, E, (E, A)] = _.evalMap(e => doNext(e).tupleLeft(e))

  /** Feeds a stream of events into this `EventState` and returns the final state.
    * If you don't have a `Concurrent` constraint, you are probably better off using `doNextStream` or `doNext` directly.
    */
  def doNext(eventStream: Stream[F, E])(implicit F: Concurrent[F]): F[A] =
    eventStream.through(hookup).compile.drain >> get

  /** Feeds a stream of events into this `EventState` and returns the final state as a singleton stream. */
  def doNextStream(eventStream: Stream[F, E]): Stream[F, A] = eventStream.through(hookup).drain ++ Stream.eval(get)
}

/** An `EventState` implementation that lets you `subscribe` to incoming events. */
trait EventStateTopic[F[_], E, A] extends EventState[F, E, A] {

  /** Get all emitted states from the moment of subscription. Upon subscribing, you will receive the most current state
    * and the event that generated it.
    */
  def subscribe: Stream[F, A]

  /** Pipe a series of events to this `EventState` and receive all updates. This includes updates that are not from this
    * hookup, and may be submitted elsewhere in your program.
    */
  def hookupAndSubscribe: Pipe[F, E, A]
}

/** An `EventState` implementation that lets you continuously monitor state changes. If you are looking to get every
  * single state change, look into `EventStateTopic` instead. This is strictly for scenarios where you don't necessarily
  * want every change, but want the latest changes regularly.
  */
trait SignallingEventState[F[_], E, A] extends EventState[F, E, A] {

  /** A continuous stream of this state's current value at the time of pulling. */
  def continuous: Stream[F, A]

  /** A stream of the latest updates to state. May not include all changes depending on when the current thread pulls.
    */
  def discrete: Stream[F, A]
}

object EventState {

  final class EventStatePartiallyApplied[F[_]: Concurrent]() {
    private def doNextInternal[E, A](e: E, ef: (E, A) => A, state: Ref[F, A]) =
      state.modify { internalA =>
        val next = ef(e, internalA)
        next -> next
      }
    private def finalState[E, A](state: Ref[F, A], ef: (E, A) => A) =
      new EventState[F, E, A] {
        def doNext(e: E): F[A] = doNextInternal(e, ef, state)
        def get: F[A] = state.get
      }

    /** Gives you an `EventState` that is initialized to a starting value. */
    def initial[E, A](a: A)(implicit ev: Driven[E, A]): F[EventState[F, E, Option[A]]] =
      for {
        state <- Ref[F].of(a.some)
      } yield finalState(state, (event, state) => state.handleEvent(event))

    /** Gives you an `EventState` that is initialized to a starting value and cannot be removed.
      * In the event that an event would otherwise "remove" your state, it keeps the current state value.
      */
    def total[E, A](a: A)(implicit ev: DrivenNonEmpty[E, A]): F[EventState[F, E, A]] =
      for {
        state <- Ref[F].of(a)
      } yield finalState(state, (event, state) => state.handleEvent(event))

    /** Gives you an `EventState` that is not yet initialized. */
    def empty[E, A](implicit ev: Driven[E, A]): F[EventState[F, E, Option[A]]] =
      for {
        state <- Ref[F].of(none[A])
      } yield finalState(state, (event, state) => state.handleEvent(event))

    /** Gives you an empty `EventState` powered by a user-defined function. */
    def manualEmpty[E, A](f: (E, Option[A]) => Option[A]): F[EventState[F, E, Option[A]]] =
      for {
        state <- Ref[F].of(none[A])
      } yield finalState(state, f)

    /** Gives you an `EventState` powered by a user-defined function with a starting value. */
    def manualInitial[E, A](a: A)(f: (E, Option[A]) => Option[A]): F[EventState[F, E, Option[A]]] =
      for {
        state <- Ref[F].of(a.some)
      } yield finalState(state, f)

    /** Gives you an `EventState` powered by a user-defined function with a starting value that cannot be removed.
      * The supplied function ensures that you can never remove your state and it must always have a valid value.
      */
    def manualTotal[E, A](a: A)(f: (E, A) => A): F[EventState[F, E, A]] =
      for {
        state <- Ref[F].of(a)
      } yield finalState(state, f)
  }

  /** Selects the set of constructors for a base `EventState`. Also see `topic` and `signalling`.
    */
  def apply[F[_]: Concurrent] = new EventStatePartiallyApplied[F]()

  /** Selects the set of constructors for an `EventStateTopic`, a variant of `EventState` that is subscribable. */
  def topic[F[_]: Concurrent] = new EventStateTopic.EventStateTopicPartiallyApplied[F]()

  /** Selects the set of constructors for a `SignallingEventState`, a variant of `EventState` that is continuously monitorable. */
  def signalling[F[_]: Concurrent] = new SignallingEventState.SignallingEventStatePartiallyApplied[F]()

  implicit def attachLogEventState[F[_]: Applicative, E, A]: EventStateLogOps[F, EventState[F, *, *], E, A] =
    new EventStateLogOps[F, EventState[F, *, *], E, A] {
      def attachLog(s: EventState[F, E, A])(log: EventLogSink[F, E]): EventState[F, E, A] = new EventState[F, E, A] {
        def doNext(e: E): F[A] = log.add(e) *> s.doNext(e)
        def get: F[A] = s.get
      }

      def localizeInput[EE](s: EventState[F, E, A])(f: EE => E): EventState[F, EE, A] = new EventState[F, EE, A] {
        def doNext(e: EE): F[A] = s.doNext(f(e))
        def get: F[A] = s.get
      }

      def mapState[AA](s: EventState[F, E, A])(f: A => AA): EventState[F, E, AA] = new EventState[F, E, AA] {
        def doNext(e: E): F[AA] = s.doNext(e).map(f)
        def get: F[AA] = s.get.map(f)
      }

      def attachLogAndApply(s: EventState[F, E, A])(log: FiniteEventLog[F, E, E]): Stream[F, EventState[F, E, A]] =
        log.streamOnce.through(s.hookup).drain ++ Stream.emit(attachLog(s)(log))

    }

  implicit def eventStateProfunctor[F[_]: Functor]: Profunctor[EventState[F, *, *]] =
    new Profunctor[EventState[F, *, *]] {

      def dimap[A, B, C, D](fab: EventState[F, A, B])(f: C => A)(g: B => D): EventState[F, C, D] =
        new EventState[F, C, D] {
          def doNext(e: C): F[D] = fab.doNext(f(e)).map(g)
          def get: F[D] = fab.get.map(g)
        }
    }
}

object EventStateTopic {

  final class EventStateTopicPartiallyApplied[F[_]: Concurrent]() {
    private def doNextInternal[E, A](e: E, state: Ref[F, A], ef: (E, A) => A) =
      state.updateAndGet(ef(e, _))
    private def finalState[E, A](state: Ref[F, A], topic: Topic[F, A], ef: (E, A) => A) =
      new EventStateTopic[F, E, A] {
        def doNext(e: E): F[A] = doNextInternal(e, state, ef).flatMap(a => topic.publish1(a).as(a))
        def get: F[A] = state.get
        def subscribe: Stream[F, A] = topic.subscribe(1)
        def hookupAndSubscribe: Pipe[F, E, A] = s => topic.subscribe(1).concurrently(s.through(hookup))
      }

    /** Gives you an `EventStateTopic` that is initialized to a starting value. */
    def initial[E, A](a: A)(implicit ev: Driven[E, A]): F[EventStateTopic[F, E, Option[A]]] =
      for {
        state <- Ref[F].of(a.some)
        topic <- Topic[F, Option[A]].flatTap(_.publish1(a.some))
      } yield finalState(state, topic, (event, state) => state.handleEvent(event))

    /** Gives you an `EventStateTopic` that is initialized to a starting value and cannot be removed.
      * In the event that an event would otherwise "remove" your state, it keeps the current state value.
      */
    def total[E, A](a: A)(implicit ev: DrivenNonEmpty[E, A]): F[EventStateTopic[F, E, A]] =
      for {
        state <- Ref[F].of(a)
        topic <- Topic[F, A].flatTap(_.publish1(a))
      } yield finalState(state, topic, (event, state) => state.handleEvent(event))

    /** Gives you an `EventStateTopic` that is not yet initialized. */
    def empty[E, A](implicit ev: Driven[E, A]): F[EventStateTopic[F, E, Option[A]]] =
      for {
        state <- Ref[F].of(none[A])
        topic <- Topic[F, Option[A]].flatTap(_.publish1(none[A]))
      } yield finalState(state, topic, (event, state) => state.handleEvent(event))

    /** Gives you an empty `EventStateTopic` powered by a user-defined function. */
    def manualEmpty[E, A](f: (E, Option[A]) => Option[A]): F[EventStateTopic[F, E, Option[A]]] =
      for {
        state <- Ref[F].of(none[A])
        topic <- Topic[F, Option[A]].flatTap(_.publish1(none))
      } yield finalState(state, topic, f)

    /** Gives you an `EventStateTopic` powered by a user-defined function with a starting value. */
    def manualInitial[E, A](a: A)(f: (E, Option[A]) => Option[A]): F[EventStateTopic[F, E, Option[A]]] =
      for {
        state <- Ref[F].of(a.some)
        topic <- Topic[F, Option[A]].flatTap(_.publish1(a.some))
      } yield finalState(state, topic, f)

    /** Gives you an `EventStateTopic` powered by a user-defined function with a starting value that cannot be removed.
      * The supplied function ensures that you can never remove your state and it must always have a valid value.
      */
    def manualTotal[E, A](a: A)(f: (E, A) => A): F[EventStateTopic[F, E, A]] =
      for {
        state <- Ref[F].of(a)
        topic <- Topic[F, A].flatTap(_.publish1(a))
      } yield finalState(state, topic, f)
  }

  def apply[F[_]: Concurrent] = new EventStateTopicPartiallyApplied[F]()

  implicit def attachLogEventStateTopic[F[_]: Applicative, E, A]: EventStateLogOps[F, EventStateTopic[F, *, *], E, A] =
    new EventStateLogOps[F, EventStateTopic[F, *, *], E, A] {
      def attachLog(s: EventStateTopic[F, E, A])(log: EventLogSink[F, E]): EventStateTopic[F, E, A] =
        new EventStateTopic[F, E, A] {
          def doNext(e: E): F[A] = log.add(e) *> s.doNext(e)
          def get: F[A] = s.get
          def subscribe: Stream[F, A] = s.subscribe
          def hookupAndSubscribe: fs2.Pipe[F, E, A] = _.evalTap(log.add).through(s.hookupAndSubscribe)
        }

      def localizeInput[EE](s: EventStateTopic[F, E, A])(f: EE => E): EventStateTopic[F, EE, A] =
        new EventStateTopic[F, EE, A] {
          def doNext(e: EE): F[A] = s.doNext(f(e))
          def get: F[A] = s.get
          def subscribe: Stream[F, A] = s.subscribe
          def hookupAndSubscribe: fs2.Pipe[F, EE, A] = _.map(f).through(s.hookupAndSubscribe)
        }

      def mapState[AA](s: EventStateTopic[F, E, A])(f: A => AA): EventStateTopic[F, E, AA] =
        new EventStateTopic[F, E, AA] {
          def doNext(e: E): F[AA] = s.doNext(e).map(f)
          def get: F[AA] = s.get.map(f)
          def subscribe: Stream[F, AA] = s.subscribe.map(f)
          def hookupAndSubscribe: fs2.Pipe[F, E, AA] = _.through(s.hookupAndSubscribe).map(f)
        }

      def attachLogAndApply(s: EventStateTopic[F, E, A])(
          log: FiniteEventLog[F, E, E]
      ): Stream[F, EventStateTopic[F, E, A]] =
        log.streamOnce.through(s.hookup).drain ++ Stream.emit(attachLog(s)(log))

    }

  implicit def eventStateTopicProfunctor[F[_]: Functor]: Profunctor[EventStateTopic[F, *, *]] =
    new Profunctor[EventStateTopic[F, *, *]] {

      def dimap[A, B, C, D](fab: EventStateTopic[F, A, B])(f: C => A)(g: B => D): EventStateTopic[F, C, D] =
        new EventStateTopic[F, C, D] {
          def doNext(e: C): F[D] = fab.doNext(f(e)).map(g)
          def get: F[D] = fab.get.map(g)
          def subscribe: Stream[F, D] = fab.subscribe.map(g)
          def hookupAndSubscribe: Pipe[F, C, D] = s => s.map(f).through(fab.hookupAndSubscribe).map(g)
        }
    }
}

object SignallingEventState {

  final class SignallingEventStatePartiallyApplied[F[_]: Concurrent]() {
    private def doNextInternal[E, A](e: E, ef: (E, A) => A, state: SignallingRef[F, A]) =
      state.modify { internalA =>
        val next = ef(e, internalA)
        next -> next
      }
    private def finalState[E, A](state: SignallingRef[F, A], ef: (E, A) => A) =
      new SignallingEventState[F, E, A] {
        def doNext(e: E): F[A] = doNextInternal(e, ef, state)
        def get: F[A] = state.get
        def continuous: Stream[F, A] = state.continuous
        def discrete: Stream[F, A] = state.discrete
      }

    /** Gives you a `SignallingEventState` that is initialized to a starting value. */
    def initial[E, A](a: A)(implicit ev: Driven[E, A]): F[SignallingEventState[F, E, Option[A]]] =
      for {
        state <- SignallingRef[F, Option[A]](a.some)
      } yield finalState(state, (event, state) => state.handleEvent(event))

    /** Gives you a `SignallingEventState` that is initialized to a starting value and cannot be removed.
      * In the event that an event would otherwise "remove" your state, it keeps the current state value.
      */
    def total[E, A](a: A)(implicit ev: DrivenNonEmpty[E, A]): F[SignallingEventState[F, E, A]] =
      for {
        state <- SignallingRef[F, A](a)
      } yield finalState(state, (event, state) => state.handleEvent(event))

    /** Gives you a `SignallingEventState` that is not yet initialized. */
    def empty[E, A](implicit ev: Driven[E, A]): F[SignallingEventState[F, E, Option[A]]] =
      for {
        state <- SignallingRef[F, Option[A]](none)
      } yield finalState(state, (event, state) => state.handleEvent(event))

    /** Gives you an empty `SignallingEventState` powered by a user-defined function. */
    def manualEmpty[E, A](f: (E, Option[A]) => Option[A]): F[SignallingEventState[F, E, Option[A]]] =
      for {
        state <- SignallingRef[F, Option[A]](none)
      } yield finalState(state, f)

    /** Gives you a `SignallingEventState` powered by a user-defined function with a starting value. */
    def manualInitial[E, A](a: A)(f: (E, Option[A]) => Option[A]): F[SignallingEventState[F, E, Option[A]]] =
      for {
        state <- SignallingRef[F, Option[A]](a.some)
      } yield finalState(state, f)

    /** Gives you a `SignallingEventState` powered by a user-defined function with a starting value that cannot be removed.
      * The supplied function ensures that you can never remove your state and it must always have a valid value.
      */
    def manualTotal[E, A](a: A)(f: (E, A) => A): F[SignallingEventState[F, E, A]] =
      for {
        state <- SignallingRef[F, A](a)
      } yield finalState(state, f)
  }

  def apply[F[_]: Concurrent] = new SignallingEventStatePartiallyApplied[F]()

  implicit def attachLogSignallingEventState[F[_]: Applicative, E, A]
      : EventStateLogOps[F, SignallingEventState[F, *, *], E, A] =
    new EventStateLogOps[F, SignallingEventState[F, *, *], E, A] {
      def attachLog(s: SignallingEventState[F, E, A])(log: EventLogSink[F, E]): SignallingEventState[F, E, A] =
        new SignallingEventState[F, E, A] {
          def doNext(e: E): F[A] = log.add(e) *> s.doNext(e)
          def get: F[A] = s.get
          def discrete: Stream[F, A] = s.discrete
          def continuous: Stream[F, A] = s.continuous
        }

      def localizeInput[EE](s: SignallingEventState[F, E, A])(f: EE => E): SignallingEventState[F, EE, A] =
        new SignallingEventState[F, EE, A] {
          def doNext(e: EE): F[A] = s.doNext(f(e))
          def get: F[A] = s.get
          def discrete: Stream[F, A] = s.discrete
          def continuous: Stream[F, A] = s.continuous
        }

      def mapState[AA](s: SignallingEventState[F, E, A])(f: A => AA): SignallingEventState[F, E, AA] =
        new SignallingEventState[F, E, AA] {
          def doNext(e: E): F[AA] = s.doNext(e).map(f)
          def get: F[AA] = s.get.map(f)
          def discrete: Stream[F, AA] = s.discrete.map(f)
          def continuous: Stream[F, AA] = s.continuous.map(f)
        }

      def attachLogAndApply(s: SignallingEventState[F, E, A])(
          log: FiniteEventLog[F, E, E]
      ): Stream[F, SignallingEventState[F, E, A]] =
        log.streamOnce.through(s.hookup).drain ++ Stream.emit(attachLog(s)(log))

    }

  implicit def signallingEventStateProfunctor[F[_]: Functor]: Profunctor[SignallingEventState[F, *, *]] =
    new Profunctor[SignallingEventState[F, *, *]] {

      def dimap[A, B, C, D](fab: SignallingEventState[F, A, B])(f: C => A)(g: B => D): SignallingEventState[F, C, D] =
        new SignallingEventState[F, C, D] {
          def doNext(e: C): F[D] = fab.doNext(f(e)).map(g)
          def get: F[D] = fab.get.map(g)
          def discrete: Stream[F, D] = fab.discrete.map(g)
          def continuous: Stream[F, D] = fab.continuous.map(g)
        }
    }
}
