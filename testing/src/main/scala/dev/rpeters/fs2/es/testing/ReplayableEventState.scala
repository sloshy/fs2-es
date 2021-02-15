package dev.rpeters.fs2.es.testing

import cats.FlatMap
import cats.data.Chain
import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import cats.effect.concurrent.Ref
import dev.rpeters.fs2.es._
import fs2.{Pipe, Stream}
import fs2.concurrent.Topic
import syntax._

/** A wrapper around an `EventStateTopic` for debugging purposes.
  * Stores events added using an internal event log that you can seek forward and back in.
  * If you call `doNext` or pass an event through `hookup` while you are seeking away from the latest event,
  * all later events will be dropped. Do keep this in mind.
  * Additionally, if you `seekTo` any known state and `subscribe`, the new state will be emitted on each seek.
  */
trait ReplayableEventState[F[_], E, A] extends EventStateTopic[F, E, A] {

  /** Gets the current count of events in the event log. */
  def getEventCount: F[Int]

  /** Returns the internal list of events in the order they were applied. */
  def getEvents: F[Chain[E]]

  /** Gets the current index in the event log. */
  def getIndex: F[Int]

  /** Resets the state of this EventState to the beginning.
    * Returns the initial value and clears the internal event list.
    */
  def reset: F[A]

  /** Like `reset` but also sets a new initial value from now on */
  def resetInitial(a: A): F[A]

  /** Seeks non-destructively to the initial state and keeps all events. */
  def seekToBeginning: F[A] = seekTo(0)

  /** Seeks backwards by up to `n` states, if possible.
    * Passing in a negative number will be the same as seeking forward.
    */
  def seekBackBy(n: Int)(implicit F: FlatMap[F]) = getIndex.flatMap(i => seekTo(i - n))

  /** Seeks forwards by up to `n` states, if possible.
    * Passing in a negative number will be the same as seeking backward.
    */
  def seekForwardBy(n: Int)(implicit F: FlatMap[F]) = seekBackBy(n * -1)

  /** Resets to the Nth state achieved.
    * It does this by replaying `n` events at a time and emitting the final result.
    *
    * For values `n <= 0`, returns the initial state.
    * For values higher than the current event count,
    */
  def seekTo(n: Int): F[A]

}

object ReplayableEventState {
  private final case class InternalState[E, A](events: Chain[E] = Chain.empty, index: Int = 0)
  final class ReplayableEventStatePartiallyApplied[F[_]: Sync, G[_]: Concurrent]() {

    private def doNextInternal[E, A](e: E, state: Ref[G, A], ef: (E, A) => A) =
      state.updateAndGet(ef(e, _))

    private def updateInternalState[E, A](s: InternalState[E, A], e: E) =
      if (s.events.size > s.index + 1) {
        InternalState[E, A](Chain.fromSeq(s.events.toList.take(s.index + 1)) :+ e, s.index + 1)
      } else {
        InternalState[E, A](s.events :+ e, s.index + 1)
      }

    private def finalState[E, A](
        initial: Ref[G, A],
        internalState: Ref[G, InternalState[E, A]],
        state: Ref[G, A],
        topic: Topic[G, A],
        ef: (E, A) => A
    ): ReplayableEventState[G, E, A] =
      new ReplayableEventState[G, E, A] {
        def doNext(e: E): G[A] =
          for {
            a <- doNextInternal(e, state, ef)
            _ <- internalState.update(s => updateInternalState(s, e))
            _ <- topic.publish1(a)
          } yield a

        val get: G[A] = state.get

        val subscribe: Stream[G, A] = topic.subscribe(1)

        val hookupAndSubscribe: Pipe[G, E, A] = s => topic.subscribe(1).concurrently(s.through(hookup))

        val getEventCount: G[Int] = internalState.get.map(_.events.size.toInt)

        val getEvents: G[Chain[E]] = internalState.get.map(_.events)

        val getIndex: G[Int] = internalState.get.map(_.index)

        val reset: G[A] = initial.get
          .flatTap(_ => internalState.set(InternalState(Chain.empty)))
          .flatTap(state.set)
          .flatTap(topic.publish1)

        def resetInitial(a: A): G[A] =
          internalState
            .set(InternalState(Chain.empty))
            .flatTap(_ => state.set(a))
            .flatTap(_ => topic.publish1(a))
            .as(a)

        def seekTo(n: Int): G[A] =
          internalState.modify { s =>
            val eventsToApply = s.events.toList.take(n)
            val thenDo = for {
              _ <- initial.get.flatMap(state.set)
              lastState <- eventsToApply.traverse_(e => doNextInternal(e, state, ef)) >> get
            } yield lastState

            (s.copy(index = s.events.size.min(n).toInt) -> thenDo)
          }.flatten

      }

    /** Creates a `ReplayableEventState` that is initialized to a starting value. */
    def initial[E, A](a: A)(implicit driven: Driven[E, A]): F[ReplayableEventState[G, E, Option[A]]] =
      for {
        initial <- Ref.in[F, G, Option[A]](a.some)
        internal <- Ref.in[F, G, InternalState[E, Option[A]]](InternalState())
        state <- Ref.in[F, G, Option[A]](a.some)
        topic <- Topic.in[F, G, Option[A]](a.some)
      } yield finalState(
        initial,
        internal,
        state,
        topic,
        (event, state) => state.handleEvent(event)
      )

    /** Creates a `ReplayableEventState` that is initialized to a starting value and cannot be deleted. */
    def total[E, A](a: A)(implicit driven: DrivenNonEmpty[E, A]): F[ReplayableEventState[G, E, A]] =
      for {
        initial <- Ref.in[F, G, A](a)
        internal <- Ref.in[F, G, InternalState[E, A]](InternalState())
        state <- Ref.in[F, G, A](a)
        topic <- Topic.in[F, G, A](a)
      } yield finalState(
        initial,
        internal,
        state,
        topic,
        (event, state) => state.handleEvent(event)
      )

    /** Creates a `ReplayableEventState` that is not yet initialized. */
    def empty[E, A](implicit ev: Driven[E, A]): F[ReplayableEventState[G, E, Option[A]]] =
      for {
        initial <- Ref.in[F, G, Option[A]](none)
        internal <- Ref.in[F, G, InternalState[E, Option[A]]](InternalState())
        state <- Ref.in[F, G, Option[A]](none)
        topic <- Topic.in[F, G, Option[A]](none)
      } yield finalState(initial, internal, state, topic, (event, state) => state.handleEvent(event))

    /** Creates a `ReplayableEventState` that is not yet initialized, powered by a user-defined function. */
    def manualEmpty[E, A](f: (E, Option[A]) => Option[A]): F[ReplayableEventState[G, E, Option[A]]] =
      for {
        initial <- Ref.in[F, G, Option[A]](none)
        internal <- Ref.in[F, G, InternalState[E, Option[A]]](InternalState())
        state <- Ref.in[F, G, Option[A]](none)
        topic <- Topic.in[F, G, Option[A]](none)
      } yield finalState(initial, internal, state, topic, f)

    /** Creates a `ReplayableEventState` that is initialized to a starting value, powered by a user-defined function. */
    def manualInitial[E, A](a: A)(f: (E, Option[A]) => Option[A]): F[ReplayableEventState[G, E, Option[A]]] =
      for {
        initial <- Ref.in[F, G, Option[A]](a.some)
        internal <- Ref.in[F, G, InternalState[E, Option[A]]](InternalState())
        state <- Ref.in[F, G, Option[A]](a.some)
        topic <- Topic.in[F, G, Option[A]](a.some)
      } yield finalState(initial, internal, state, topic, f)

    /** Creates a `ReplayableEventState` that is initialized to a starting value and cannot be deleted, powered by a user-defined function. */
    def manualTotal[E, A](a: A)(f: (E, A) => A): F[ReplayableEventState[G, E, A]] =
      for {
        initial <- Ref.in[F, G, A](a)
        internal <- Ref.in[F, G, InternalState[E, A]](InternalState())
        state <- Ref.in[F, G, A](a)
        topic <- Topic.in[F, G, A](a)
      } yield finalState(initial, internal, state, topic, f)
  }
  def apply[F[_]: Concurrent] = new ReplayableEventStatePartiallyApplied[F, F]()

  def in[F[_]: Sync, G[_]: Concurrent] = new ReplayableEventStatePartiallyApplied[F, G]()
}
