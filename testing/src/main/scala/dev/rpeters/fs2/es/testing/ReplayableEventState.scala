package dev.rpeters.fs2.es.testing

import cats.data.Chain
import cats.effect.Concurrent
import cats.implicits._
import cats.effect.concurrent.Ref
import dev.rpeters.fs2.es.EventState.EventProcessor
import dev.rpeters.fs2.es.EventState
import dev.rpeters.fs2.es.EventStateTopic
import fs2.{Pipe, Stream}

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

  /** Seeks non-destructively to the initial state and keeps all events. */
  def seekToBeginning: F[A] = seekTo(0)

  /** Resets to the Nth state achieved.
    * It does this by replaying `n` events at a time and emitting the final result.
    *
    * For values `n <= 0`, returns the initial state.
    * For values higher than the current event count,
    */
  def seekTo(n: Int): F[A]

}

object ReplayableEventState {
  private final case class InternalState[E, A](events: Chain[E], state: A, index: Int = 0)
  final class ReplayableEventStatePartiallyApplied[F[_]: Concurrent]() {

    /** Creates a `ReplayableEventState` with the given starting value. */
    def initial[E, A](a: A)(ef: EventProcessor[E, A]) = {
      val initialState = InternalState(Chain.empty[E], a)
      for {
        stateRef <- Ref[F].of(initialState)
        esRef <- EventState.topic[F].initial(a)(ef).flatMap(Ref[F].of)
      } yield new ReplayableEventState[F, E, A] {

        def doNext(e: E): F[A] =
          for {
            es <- esRef.get
            next <- es.doNext(e)
            _ <- stateRef.update { s =>
              if (s.events.size > (s.index + 1)) {
                s.copy(
                  events = Chain.fromSeq(s.events.toList.take(s.index + 1)) :+ e,
                  state = next,
                  index = s.index + 1
                )
              } else {
                s.copy(
                  events = s.events :+ e,
                  state = next,
                  index = s.index + 1
                )
              }
            }
          } yield next

        def get: F[A] = esRef.get.flatMap(_.get)

        def hookup: Pipe[F, E, A] = _.evalMap(doNext)

        def hookupWithInput: Pipe[F, E, (E, A)] = _.evalMap(e => doNext(e).tupleLeft(e))

        def hookupAndSubscribe: Pipe[F, E, A] = { s =>
          Stream.eval(esRef.get).flatMap { est =>
            est.subscribe.concurrently(s.through(hookup))
          }
        }

        def subscribe: Stream[F, A] = Stream.eval(esRef.get).flatMap(_.subscribe)

        val getEvents: F[Chain[E]] = stateRef.get.map(_.events)

        val getEventCount: F[Int] = getEvents.map(_.size.toInt)

        val getIndex: F[Int] = stateRef.get.map(_.index)

        val reset: F[A] =
          EventState.topic[F].initial(a)(ef).flatMap(esRef.set) >> stateRef.set(initialState).as(initialState.state)

        def seekTo(n: Int): F[A] =
          for {
            currentState <- stateRef.get
            newEs <- EventState[F].initial(a)(ef)
            eventsToApply = currentState.events.toList.take(n)
            lastState <- eventsToApply.traverse_(newEs.doNext) >> newEs.get
            _ <- stateRef.update { s =>
              s.copy(
                state = lastState,
                index = s.events.size.min(n.toLong).toInt
              )
            }
          } yield lastState

      }
    }

    /** Creates a `ReplayableEventState` that is restored from an existing stream of events. */
    def hydrated[E, A](initial: A, hydrator: Stream[F, E])(ef: EventProcessor[E, A]) = ???

    /** Like `hydrated`, but returns the result in an FS2 `Stream`. */
    def hydratedStream[E, A](initial: A, hydreator: Stream[F, E])(ef: EventProcessor[E, A]) = ???
  }
  def apply[F[_]: Concurrent] = new ReplayableEventStatePartiallyApplied[F]()
}
