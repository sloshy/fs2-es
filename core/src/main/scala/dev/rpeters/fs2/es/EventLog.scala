package dev.rpeters.fs2.es

import cats.data.{Chain, NonEmptyMap}
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.syntax.all._
import fs2.{Chunk, Pipe, Stream}
import fs2.concurrent.Topic
import syntax._

/** A standard interface for accessing your event log.
  *
  * The `In` type defines the type of all events going into your log.
  * The `Out` type defines the type of all events that you stream out of your log.
  * In many cases, these will be the same, but it is possible to map the input/output types.
  */
trait EventLog[F[_], In, Out] { self =>

  /** Add an event `E` to your event log. Should be appended to the tail of your log, and nowhere else.
    *
    * @param e The event to be added to your event log.
    * @return `F[Unit]` if nothing abnormal occurs, but may throw depending on the implementation.
    */
  def add(e: In): F[Unit]

  /** Stream all events from your event log.
    *
    * Assumes that all events will apply to your state.
    * Useful if your event log corresponds to a single aggregate root.
    * That is, you do not have more than one "state" that your log keeps track of.
    *
    * @return A stream of all events from your event log. May be repeatedly evaluated for updated results.
    */
  def stream: Stream[F, Out]

  /** Computes a final state value from your event log.
    *
    * Assumes that all events will apply to your state.
    * Useful if your event log corresponds to a single aggregate root.
    * That is, you do not have more than one "state" that your log keeps track of.
    *
    * @return A singleton stream of a final state value, assuming it exists.
    */
  def getState[A](implicit driven: Driven[Out, A]): Stream[F, Option[A]] =
    stream.fold(none[A])(_.handleEvent(_))

  /** Computes a final state value from your event log, starting with an initial state.
    *
    * @param initial Your initial starting state.
    * @return A singleton stream of a final state value after applying all events to your starting state.
    */
  def getState[A](initial: A)(implicit driven: DrivenNonEmpty[Out, A]): Stream[F, Option[A]] =
    stream.fold(initial.some) { (state, event) => state.flatMap(_.handleEvent(event)) }

  /** Gets a single state as defined by the key you are filtering by.
    *
    * @param key The key you wish to filter for.
    * @return A singleton stream of your final state value, assuming it exists.
    */
  def getOneState[K, A](
      key: K
  )(implicit keyedState: KeyedState[K, Out, A]): Stream[F, Option[A]] =
    stream.filter(_.getKey == key).fold(none[A])(_.handleEvent(_))

  /** Gets a single state as defined by the key you are filtering by and an initial state.
    *
    * @param initial The initial state value you are starting with.
    * @param key The key you wish to filter for.
    * @return A singleton stream of your final state value after applying all events to your starting state.
    */
  def getOneState[K, A](initial: A, key: K)(implicit keyedState: KeyedStateNonEmpty[K, Out, A]): Stream[F, Option[A]] =
    stream.filter(_.getKey == key).fold(initial.some) { (state, event) => state.flatMap(_.handleEvent(event)) }

  /** Computes final state values from your event log and groups them by-key.
    *
    * @return A singleton stream of all final states grouped by-key from this event log.
    */
  def getKeyedState[K, A](implicit keyedState: KeyedState[K, Out, A]): Stream[F, Map[K, A]] =
    stream.fold(Map.empty[K, A]) { (stateMap, event) =>
      val key = event.getKey
      stateMap.get(key).handleEvent(event) match {
        case Some(v) => stateMap + (key -> v)
        case None    => stateMap - key
      }
    }

  /** Computes final state values from your event log and groups them by-key, but only for specific states.
    *
    * This version will only apply states to the keyed states that exist in your initial map.
    * If a state does not exist in the map, it will not be initialized.
    *
    * @param initial A `NonEmptyMap` of keyed states you wish to apply events to.
    * @return A singleton stream of a map containing all of your pre-specified keys and their resulting states.
    */
  def getKeyedState[K, A](
      initial: NonEmptyMap[K, A]
  )(implicit keyedState: KeyedStateNonEmpty[K, Out, A]): Stream[F, NonEmptyMap[K, A]] =
    stream.fold(initial) { (stateMap, event) =>
      val key = event.getKey
      stateMap(key) match {
        case Some(a) =>
          a.handleEvent(event).map(state => stateMap.add(key -> state)).getOrElse(stateMap)
        case None =>
          stateMap
      }
    }

  /** Modify the event input type of this log by showing how you convert from
    * a different type to the existing one.
    *
    * Like `local` on `cats.data.Kleisli`, is sort of an "inverse map" function
    * that lets you change the type of events that you insert into this log.
    *
    * @param f A function from `A` to the current input type.
    * @return A new `EventLog` that takes in `A` values and converts them before persisting.
    */
  def localizeEvent[A](f: A => In) = new EventLog[F, A, Out] {
    def add(e: A): F[Unit] = self.add(f(e))
    def stream: Stream[F, Out] = self.stream
  }

  /** Map the output stream of events.
    *
    * @param f A function from `Stream[F, Out]` to `Stream[F, A]` (aka an `fs2.Pipe`)
    * @return A new `EventLog` where the stream of events is modified as you describe.
    */
  def mapStream[A](f: Pipe[F, Out, A]) = new EventLog[F, In, A] {
    def add(e: In): F[Unit] = self.add(e)
    def stream: Stream[F, A] = f(self.stream)
  }
}

object EventLog {

  /** Create an in-memory `EventLog`. */
  def inMemory[F[_]: Sync, E] = inMemory[F, F, E]

  /** Create an in-memory `EventLog` using two different effect types. */
  def inMemory[F[_]: Sync, G[_]: Sync, E] = Ref.in[F, G, Chain[E]](Chain.nil).map { ref =>
    new EventLog[G, E, E] {
      def add(e: E): G[Unit] = ref.update(_ :+ e)
      def stream: Stream[G, E] =
        Stream.eval(ref.get).map(Chunk.chain).flatMap(Stream.chunk)
    }
  }
}
