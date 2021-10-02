package dev.rpeters.fs2.es

import cats.{ApplicativeThrow, Contravariant, Functor}
import cats.arrow.Profunctor
import cats.data.{Chain, NonEmptyMap, NonEmptySet}
import cats.effect.kernel.{Concurrent, Deferred, Ref}
import cats.effect.std.Semaphore
import cats.syntax.all._
import fs2.{Chunk, Pipe, Stream}
import fs2.concurrent.{Channel, Topic}
import fs2.Pull

import KeyedContinuousEventLogSource.{KeyedContinuousStreamResult, NonEmptyKeyedContinuousStreamResult}
import syntax._

trait EventLogSink[F[_], E] { self =>

  /** Add an event `E` to your event log. Should be appended to the tail of your log, and nowhere else.
    *
    * @param e The event to be added to your event log.
    * @return `F[Unit]` if nothing abnormal occurs, but may throw depending on the implementation.
    */
  def add(e: E): F[Unit]

  /** Combines this `EventLogSink` with a given `EventLogSource` to form a full `EventLog`.
    * Useful if you make multiple calls to `localizeEvent` or `mapStream` and want to recombine the two halves.
    *
    * @param source An `EventLogSource` for streaming events.
    * @return An `EventLog` combining both this sink and source together.
    */
  // def withSource[A](source: EventLogSource[F, A]): EventLog[F, E, A] = EventLog.from(this, source)

  /** Combines this `EventLogSink` with a given `ContinuousEventLogSource` to form a full `ContinuousEventLog`.
    * Useful if you make multiple calls to `localizeEvent` or `mapStream` and want to recombine the two halves.
    *
    * @param source An `EventLogSource` for streaming events.
    * @return An `EventLog` combining both this sink and source together.
    */
  // def withContinuousSource[A](source: EventLogSource[F, A]): EventLog[F, E, A] = EventLog.from(this, source)
}

object EventLogSink {
  implicit def contravariantEventLogSink[F[_]]: Contravariant[EventLogSink[F, *]] =
    new Contravariant[EventLogSink[F, *]] {
      def contramap[A, B](fa: EventLogSink[F, A])(f: B => A): EventLogSink[F, B] = new EventLogSink[F, B] {
        def add(e: B): F[Unit] = fa.add(f(e))
      }
    }
}

/** A source of events from an event log that can be read from start to finish.
  * Implements the method `streamOnce`, from which you have multiple other methods for retrieving state from events.
  * If you would like to stream from the event log continuously, see `ContinuousEventLogSource`.
  */
trait FiniteEventLogSource[F[_], E] {

  /** Stream all events from your event log, up to the latest available event, before completing. */
  def streamOnce: Stream[F, E]

  /** Computes a final state value from your event log.
    *
    * Applies all events to a single state value, and if at least one event is seen, results in a singleton stream containing your final state.
    * Otherwise, the stream will contain a single `None`.
    * Useful if your event log corresponds to a single result value or "aggregate root".
    *
    * @return A singleton stream of a final state value, assuming it exists, or `None`.
    */
  def getState[A](implicit driven: Driven[E, A]): Stream[F, Option[A]] =
    streamOnce.fold(none[A])(_.handleEvent(_))

  /** Computes a final state value from your event log, starting with an initial state.
    *
    * Applies all events to a single state value, starting with your initial event supplied.
    * Useful if your event log corresponds to a single result value or "aggregate root".
    *
    * @param initial Your initial starting state.
    * @return A singleton stream of a final state value after applying all events to your starting state.
    */
  def getState[A](initial: A)(implicit driven: DrivenNonEmpty[E, A]): Stream[F, A] =
    streamOnce.fold(initial)(_.handleEvent(_))

}

object FiniteEventLogSource {
  implicit def finiteEventLogSourceFunctor[F[_]]: Functor[FiniteEventLogSource[F, *]] =
    new Functor[FiniteEventLogSource[F, *]] {
      def map[A, B](fa: FiniteEventLogSource[F, A])(f: A => B): FiniteEventLogSource[F, B] =
        new FiniteEventLogSource[F, B] {
          def streamOnce: Stream[F, B] = fa.streamOnce.map(f)
        }
    }
}

/** A variant of `FiniteEventLogSource` that also contains a known key type. */
trait KeyedFiniteEventLogSource[F[_], K, E] extends FiniteEventLogSource[F, E] {

  /** A variant of `streamOnce` that filters by key. Can be overridden in the implementation for efficiency. */
  def streamOnceForKey(key: K)(implicit keyed: Keyed[K, E]) =
    streamOnce.filter(_.getKey == key)

  /** A variant of `streamOnce` that filters by multiple keys. Can be overridden in the implementation for efficiency. */
  def streamOnceForKeyBatch(keys: NonEmptySet[K])(implicit keyed: Keyed[K, E]) =
    streamOnce.filter(e => keys.contains(e.getKey))

  /** Gets a single state as defined by the key you are filtering by.
    *
    * @param key The key you wish to filter for.
    * @return A singleton stream of your final state value, assuming it exists.
    */
  def getOneState[A](
      key: K
  )(implicit keyedState: KeyedState[K, E, A]): Stream[F, Option[A]] =
    streamOnceForKey(key).fold(none[A])(_.handleEvent(_))

  /** Gets a single state as defined by the key you are filtering by and an initial state.
    *
    * @param key The key you wish to filter for.
    * @param initial The initial state value you are starting with.
    * @return A singleton stream of your final state value after applying all events to your starting state.
    */
  def getOneState[A](key: K, initial: A)(implicit keyedState: KeyedStateNonEmpty[K, E, A]): Stream[F, A] =
    streamOnceForKey(key).fold(initial)(_.handleEvent(_))

  /** Computes all final state values from your event log and groups them by-key.
    *
    * @return A singleton stream of all final states grouped by-key from this event log.
    */
  def getAllKeyedStates[A](implicit keyedState: KeyedState[K, E, A]): Stream[F, Map[K, A]] =
    streamOnce.fold(Map.empty[K, A]) { (stateMap, event) =>
      val key = event.getKey
      stateMap.get(key).handleEvent(event) match {
        case Some(v) => stateMap + (key -> v)
        case None    => stateMap - key
      }
    }

  /** Computes all final state values from your event log and groups them by key.
    * This version assumes that all states must exist, and can never be removed, so they must have an initial value.
    *
    * @param initial A function where, for some key, you can derive an initial state.
    * @return A singleton stream of all final states grouped by-key from this event log.
    */
  def getAllKeyedStates[A](initial: K => A)(implicit keyedState: KeyedStateNonEmpty[K, E, A]): Stream[F, Map[K, A]] =
    streamOnce.fold(Map.empty[K, A]) { (stateMap, event) =>
      val key = event.getKey
      stateMap.get(key) match {
        case Some(state) => stateMap + (key -> state.handleEvent(event))
        case None        => stateMap + (key -> initial(key).handleEvent(event))
      }
    }

  /** Computes all final state values from your event log, but only of the keys you specify.
    *
    * @param keys All of the keys you wish to filter for in your result batch.
    * @return A singleton stream of a map of all the final states of your specified keys.
    */
  def getKeyedStateBatch[A](keys: NonEmptySet[K])(implicit keyedState: KeyedState[K, E, A]): Stream[F, Map[K, A]] =
    streamOnceForKeyBatch(keys).fold(Map.empty[K, A]) { (stateMap, event) =>
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
  def getKeyedStateBatch[A](
      initial: NonEmptyMap[K, A]
  )(implicit keyedState: KeyedStateNonEmpty[K, E, A]): Stream[F, NonEmptyMap[K, A]] =
    streamOnceForKeyBatch(initial.keys).fold(initial) { (stateMap, event) =>
      val key = event.getKey
      stateMap(key) match {
        case Some(a) =>
          stateMap.add(key -> a.handleEvent(event))
        case None =>
          stateMap
      }
    }
}

object KeyedFiniteEventLogSource {
  implicit def keyedFiniteEventLogSourceFunctor[F[_], K]: Functor[KeyedFiniteEventLogSource[F, K, *]] =
    new Functor[KeyedFiniteEventLogSource[F, K, *]] {
      def map[A, B](fa: KeyedFiniteEventLogSource[F, K, A])(f: A => B): KeyedFiniteEventLogSource[F, K, B] =
        new KeyedFiniteEventLogSource[F, K, B] {
          def streamOnce: Stream[F, B] = fa.streamOnce.map(f)
        }
    }
}

/** An event log with both a source and sink that has a known end (or last event).
  *
  * The `In` type defines the type of all events going into your log.
  * The `E` type defines the type of all events that you stream out of your log.
  * In many cases, `In` and `E` will be the same, but it is possible to map the input/output types to change them using `contramap` and `map` respectively.
  */
trait FiniteEventLog[F[_], In, Out] extends EventLogSink[F, In] with FiniteEventLogSource[F, Out]

object FiniteEventLog {

  def from[F[_], In, Out](sink: EventLogSink[F, In], source: FiniteEventLogSource[F, Out]) =
    new FiniteEventLog[F, In, Out] {
      def add(e: In): F[Unit] = sink.add(e)
      def streamOnce: Stream[F, Out] = source.streamOnce
    }

  implicit def finiteEventLogProfunctor[F[_]]: Profunctor[FiniteEventLog[F, *, *]] =
    new Profunctor[FiniteEventLog[F, *, *]] {
      override def rmap[A, B, C](io: FiniteEventLog[F, A, B])(f: B => C): FiniteEventLog[F, A, C] =
        new FiniteEventLog[F, A, C] {
          def add(e: A): F[Unit] = io.add(e)
          def streamOnce: Stream[F, C] = io.streamOnce.map(f)
        }

      override def lmap[A, B, C](io: FiniteEventLog[F, A, B])(f: C => A): FiniteEventLog[F, C, B] =
        new FiniteEventLog[F, C, B] {
          def add(e: C): F[Unit] = io.add(f(e))
          def streamOnce: Stream[F, B] = io.streamOnce
        }

      def dimap[A, B, C, D](io: FiniteEventLog[F, A, B])(f: C => A)(g: B => D): FiniteEventLog[F, C, D] =
        new FiniteEventLog[F, C, D] {
          def add(e: C): F[Unit] = io.add(f(e))
          def streamOnce: Stream[F, D] = io.streamOnce.map(g)
        }

    }
}

/** A `FiniteEventLog` that has a known last event as well as keys for every event.
  *
  * This enables you to use certain keyed operations to get the final values of your state.
  *
  * The `K` type defines the key type of your events.
  * The `In` type defines the type of all events going into your log.
  * The `E` type defines the type of all events that you stream out of your log.
  * In many cases, `In` and `E` will be the same, but it is possible to map the input/output types using `contramap` and `map` respectively.
  */
trait KeyedFiniteEventLog[F[_], K, In, Out] extends EventLogSink[F, In] with KeyedFiniteEventLogSource[F, K, Out]

object KeyedFiniteEventLog {

  def from[F[_], K, In, Out](sink: EventLogSink[F, In], source: KeyedFiniteEventLogSource[F, K, Out]) =
    new KeyedFiniteEventLog[F, K, In, Out] {
      def add(e: In): F[Unit] = sink.add(e)
      def streamOnce: Stream[F, Out] = source.streamOnce
    }

  implicit def keyedFiniteEventLogProfunctor[F[_], K]: Profunctor[KeyedFiniteEventLog[F, K, *, *]] =
    new Profunctor[KeyedFiniteEventLog[F, K, *, *]] {
      override def rmap[A, B, C](io: KeyedFiniteEventLog[F, K, A, B])(f: B => C): KeyedFiniteEventLog[F, K, A, C] =
        new KeyedFiniteEventLog[F, K, A, C] {
          def add(e: A): F[Unit] = io.add(e)
          def streamOnce: Stream[F, C] = io.streamOnce.map(f)
        }

      override def lmap[A, B, C](io: KeyedFiniteEventLog[F, K, A, B])(f: C => A): KeyedFiniteEventLog[F, K, C, B] =
        new KeyedFiniteEventLog[F, K, C, B] {
          def add(e: C): F[Unit] = io.add(f(e))
          def streamOnce: Stream[F, B] = io.streamOnce
        }

      def dimap[A, B, C, D](
          io: KeyedFiniteEventLog[F, K, A, B]
      )(f: C => A)(g: B => D): KeyedFiniteEventLog[F, K, C, D] =
        new KeyedFiniteEventLog[F, K, C, D] {
          def add(e: C): F[Unit] = io.add(f(e))
          def streamOnce: Stream[F, D] = io.streamOnce.map(g)
        }

    }
}

/** A source that allows you to continuously stream events as they are published.
  *
  * As opposed to a `FiniteEventLogSource`, there is no known "end point" due to some implementation detail.
  * Because of this, you are not able to get individual states from this event log, but you instead must continuously process events as they come in.
  */
trait ContinuousEventLogSource[F[_], E] {

  /** Continuously streams events from an event log.
    * As opposed to `streamOnce` on `FiniteEventLogSource`, this will continue giving you updates as they come in.
    */
  def streamContinuously: Stream[F, E]

  /** Continuously computes states from your event log.
    *
    * Assumes that all events will apply to a single state value.
    *
    * @return A continuous stream of any state values, assuming any exist.
    */
  def getStateContinuously[A](implicit driven: Driven[E, A]): Stream[F, Option[A]] =
    streamContinuously.scan(none[A])(_.handleEvent(_)).drop(1)

  /** Computes a final state value from your event log, starting with an initial state.
    *
    * Assumes that all events will apply to a single state value.
    *
    * @param initial Your initial starting state.
    * @return A singleton stream of a final state value after applying all events to your starting state.
    */
  def getStateContinuously[A](initial: A)(implicit driven: DrivenNonEmpty[E, A]): Stream[F, A] =
    streamContinuously.scan(initial)(_.handleEvent(_)).drop(1)

}

object ContinuousEventLogSource {
  implicit def continuousEventLogSourceFunctor[F[_]]: Functor[ContinuousEventLogSource[F, *]] =
    new Functor[ContinuousEventLogSource[F, *]] {
      def map[A, B](fa: ContinuousEventLogSource[F, A])(f: A => B): ContinuousEventLogSource[F, B] =
        new ContinuousEventLogSource[F, B] {
          def streamContinuously: Stream[F, B] = fa.streamContinuously.map(f)
        }
    }
}

/** A `ContinuousEventLogSource` where every event has a known key.
  *
  * This allows you to stream evvents and states based on key filters, and can be optimized per-implementation using downstream filtering mechanisms.
  */
trait KeyedContinuousEventLogSource[F[_], K, E] extends ContinuousEventLogSource[F, E] {

  /** A version of `streamContinuously` that filters by-key. */
  def streamContinuouslyForKey(key: K)(implicit keyed: Keyed[K, E]) =
    streamContinuously.filter(_.getKey == key)

  /** A version of `streamContinuously` that filters by multiple keys at once. */
  def streamContinuouslyForKeyBatch(keys: NonEmptySet[K])(implicit keyed: Keyed[K, E]) =
    streamContinuously.filter(e => keys.contains(e.getKey))

  /** Filters by a given key and produces a stream of every known state that occurs.
    *
    * @param key The key you wish to filter for.
    * @return A stream of all known states for the key you filter for, along with the event that produced that state.
    */
  def getOneStateContinuously[A](
      key: K
  )(implicit keyedState: KeyedState[K, E, A]): Stream[F, (E, Option[A])] =
    streamContinuouslyForKey(key)
      .scan(none[E] -> none[A]) { case ((optE, optA), next) => next.some -> optA.handleEvent(next) }
      .drop(1)
      .map { case (optE, optA) => optE.map(_ -> optA) }
      .unNone

  /** Filters by a given key and produces a stream of every known state that occurs, using an initial state value.
    *
    * Uses an initial state value, so that the resulting state is not optional and cannot be erased (unless that c)
    *
    * @param key The key you wish to filter for.
    * @param initial The initial state value you are starting with.
    * @return A stream of all known states for the key you filter for, along with the event that produced that state.
    */
  def getOneStateContinuously[A](key: K, initial: A)(implicit
      keyedState: KeyedStateNonEmpty[K, E, A]
  ): Stream[F, (E, A)] =
    streamContinuouslyForKey(key)
      .scan(none[E] -> initial) { case ((optE, a), next) => next.some -> a.handleEvent(next) }
      .drop(1)
      .collect { case (Some(e), a) => e -> a }

  /** Computes all states from your event log by-key.
    *
    * If an event would have caused a state to be `None`, that entry will not exist in the output map.
    *
    * @return A continuous stream of all states by-key from this event log, along with the most recent event that modified any state and the specific state modified.
    */
  def getAllKeyedStateContinuously[A](implicit
      keyedState: KeyedState[K, E, A]
  ): Stream[F, KeyedContinuousStreamResult[K, E, A]] =
    streamContinuously
      .scan(none[KeyedContinuousStreamResult[K, E, A]]) { (maybeLastState, next) =>
        val key = next.getKey
        val stateMap = maybeLastState.map(_.allStates).getOrElse(Map.empty)
        stateMap.get(key).handleEvent(next) match {
          case Some(v) => KeyedContinuousStreamResult[K, E, A](next, v.some, (stateMap + (key -> v))).some
          case None    => KeyedContinuousStreamResult[K, E, A](next, none, (stateMap - key)).some
        }
      }
      .drop(1)
      .unNone

  /** Computes all states from your event log by-key, with an initial set of starting states.
    *
    * @param initial A `NonEmptyMap` of keyed states you wish to apply events to.
    * @return A continuous stream of a map containing all of your pre-specified keys and their states, along with the most recent event that modified any state.
    */
  def getAllKeyedStateContinuously[A](
      initial: NonEmptyMap[K, A]
  )(implicit
      keyedState: KeyedStateNonEmpty[K, E, A]
  ): Stream[F, NonEmptyKeyedContinuousStreamResult[K, E, A]] =
    streamContinuously
      .scan(none[A] -> none[(E, NonEmptyMap[K, A])]) { case ((maybeLastValue, maybeState), next) =>
        val key = next.getKey
        val stateMap = maybeState.map(_._2).getOrElse(initial)
        stateMap(key) match {
          case Some(a) =>
            val nextValue = a.handleEvent(next)
            nextValue.some -> (next, stateMap.add(key -> nextValue)).some
          case None =>
            none -> (next, stateMap).some
        }
      }
      .drop(1)
      .collect { case (Some(lastValue), Some(state)) =>
        NonEmptyKeyedContinuousStreamResult[K, E, A](state._1, lastValue, state._2)
      }

  /** Produces a stream of states filtered by the keys you specify.
    *
    * @param keys All of the keys you wish to filter for in your result stream.
    * @return A continuous stream of all the states of your specified keys along with the event that triggered them, and the most recently changed state.
    */
  def getKeyedStateBatchContinuously[A](
      keys: NonEmptySet[K]
  )(implicit keyedState: KeyedState[K, E, A]): Stream[F, KeyedContinuousStreamResult[K, E, A]] =
    streamContinuouslyForKeyBatch(keys)
      .scan(none[KeyedContinuousStreamResult[K, E, A]]) { (stateOpt, next) =>
        val key = next.getKey
        val stateMap = stateOpt.map(_.allStates).getOrElse(Map.empty)
        stateMap.get(key).handleEvent(next) match {
          case Some(v) => KeyedContinuousStreamResult[K, E, A](next, v.some, (stateMap + (key -> v))).some
          case None    => KeyedContinuousStreamResult[K, E, A](next, none, (stateMap - key)).some
        }
      }
      .drop(1)
      .unNone

  /** Produces a stream of states filtered by the keys of the key/value pairs you specify.
    *
    * This version will only apply states to the keyed states that exist in your initial map.
    * If a state does not exist in the map, it will not be initialized.
    *
    * @param initial A `NonEmptyMap` of keyed states you wish to apply events to.
    * @return A stream of maps containing all of your pre-specified keys and their states.
    */
  def getKeyedStateBatchContinuously[A](
      initial: NonEmptyMap[K, A]
  )(implicit keyedState: KeyedStateNonEmpty[K, E, A]): Stream[F, NonEmptyKeyedContinuousStreamResult[K, E, A]] =
    streamContinuouslyForKeyBatch(initial.keys)
      .scan(none[A] -> none[(E, NonEmptyMap[K, A])]) { case ((maybeLastValue, maybeLastState), next) =>
        val key = next.getKey
        val stateMap = maybeLastState.map(_._2).getOrElse(initial)
        stateMap(key) match {
          case Some(a) =>
            val nextValue = a.handleEvent(next)
            nextValue.some -> (next, stateMap.add(key -> nextValue)).some
          case None =>
            none -> (next, stateMap).some
        }
      }
      .drop(1)
      .collect { case (Some(lastValue), Some(state)) =>
        NonEmptyKeyedContinuousStreamResult[K, E, A](state._1, lastValue, state._2)
      }
}

object KeyedContinuousEventLogSource {

  /** The results of streaming from a keyed continuous stream. */
  final case class KeyedContinuousStreamResult[K, E, A](event: E, state: Option[A], allStates: Map[K, A])

  /** The results of streaming from a keyed continuous stream after providing an initial value, for non-empty domains. */
  final case class NonEmptyKeyedContinuousStreamResult[K, E, A](event: E, state: A, allStates: NonEmptyMap[K, A])

  implicit def keyedContinuousEventLogSourceFunctor[F[_], K]: Functor[KeyedContinuousEventLogSource[F, K, *]] =
    new Functor[KeyedContinuousEventLogSource[F, K, *]] {
      def map[A, B](fa: KeyedContinuousEventLogSource[F, K, A])(f: A => B): KeyedContinuousEventLogSource[F, K, B] =
        new KeyedContinuousEventLogSource[F, K, B] {
          def streamContinuously: Stream[F, B] = fa.streamContinuously.map(f)
        }
    }
}

/** An event log that allows for streaming events and states continuously, without an end. Also see `FiniteEventLog`. */
trait ContinuousEventLog[F[_], In, Out] extends EventLogSink[F, In] with ContinuousEventLogSource[F, Out]

object ContinuousEventLog {
  implicit def continuousEventLogProfunctor[F[_]]: Profunctor[ContinuousEventLog[F, *, *]] =
    new Profunctor[ContinuousEventLog[F, *, *]] {
      override def rmap[A, B, C](io: ContinuousEventLog[F, A, B])(f: B => C): ContinuousEventLog[F, A, C] =
        new ContinuousEventLog[F, A, C] {
          def add(e: A): F[Unit] = io.add(e)
          def streamContinuously: Stream[F, C] = io.streamContinuously.map(f)
        }

      override def lmap[A, B, C](io: ContinuousEventLog[F, A, B])(f: C => A): ContinuousEventLog[F, C, B] =
        new ContinuousEventLog[F, C, B] {
          def add(e: C): F[Unit] = io.add(f(e))
          def streamContinuously: Stream[F, B] = io.streamContinuously
        }

      def dimap[A, B, C, D](io: ContinuousEventLog[F, A, B])(f: C => A)(g: B => D): ContinuousEventLog[F, C, D] =
        new ContinuousEventLog[F, C, D] {
          def add(e: C): F[Unit] = io.add(f(e))
          def streamContinuously: Stream[F, D] = io.streamContinuously.map(g)
        }

    }
}

/** A `ContinuousEventLog` where every event contains keys. */
trait KeyedContinuousEventLog[F[_], K, In, Out]
    extends EventLogSink[F, In]
    with KeyedContinuousEventLogSource[F, K, Out]

object KeyedContinuousEventLog {
  implicit def keyedContinuousEventLogProfunctor[F[_], K]: Profunctor[KeyedContinuousEventLog[F, K, *, *]] =
    new Profunctor[KeyedContinuousEventLog[F, K, *, *]] {
      override def rmap[A, B, C](
          io: KeyedContinuousEventLog[F, K, A, B]
      )(f: B => C): KeyedContinuousEventLog[F, K, A, C] =
        new KeyedContinuousEventLog[F, K, A, C] {
          def add(e: A): F[Unit] = io.add(e)
          def streamContinuously: Stream[F, C] = io.streamContinuously.map(f)
        }

      override def lmap[A, B, C](
          io: KeyedContinuousEventLog[F, K, A, B]
      )(f: C => A): KeyedContinuousEventLog[F, K, C, B] =
        new KeyedContinuousEventLog[F, K, C, B] {
          def add(e: C): F[Unit] = io.add(f(e))
          def streamContinuously: Stream[F, B] = io.streamContinuously
        }

      def dimap[A, B, C, D](
          io: KeyedContinuousEventLog[F, K, A, B]
      )(f: C => A)(g: B => D): KeyedContinuousEventLog[F, K, C, D] =
        new KeyedContinuousEventLog[F, K, C, D] {
          def add(e: C): F[Unit] = io.add(f(e))
          def streamContinuously: Stream[F, D] = io.streamContinuously.map(g)
        }

    }
}

/** An event log where you can stream events and states "until the end" as well as continuously.
  * Functions as both a `FiniteEventLog` and a `ContinuousEventLog`.
  */
trait EventLog[F[_], In, Out] extends ContinuousEventLog[F, In, Out] with FiniteEventLog[F, In, Out]

object EventLog {

  /** Creates an in-memory event log that you can subscribe to or stream front-to-back.
    * Useful for testing purposes. Do not use for extended periods of time due to increased memory usage.
    *
    * @param bufferSize The size of the buffer for subscribing to continuous events published. Defaults to 512.
    * @return An `EventLog` that operates entirely in-memory.
    */
  def inMemory[F[_]: Concurrent, E](bufferSize: Int = 512) = for {
    top <- Topic[F, E]
    ref <- Concurrent[F].ref[(Chain[E], Option[Deferred[F, Unit]])](Chain.nil -> none)
  } yield {
    new EventLog[F, E, E] {
      def add(e: E): F[Unit] =
        ref
          .modify {
            case (buf, None)    => ((buf :+ e), None) -> top.publish1(e).void
            case (buf, Some(d)) => (buf, Some(d)) -> (d.get >> add(e))
          }
          .flatten
          .void

      def streamContinuously: Stream[F, E] = Stream.eval(Concurrent[F].deferred[Unit]).flatMap { newD =>
        Stream.force(ref.modify[Stream[F, E]] {
          case (buf, Some(currentD)) => (buf, Some(currentD)) -> (Stream.eval(currentD.get) >> streamContinuously)
          case (buf, None) =>
            val next = Stream.resource(top.subscribeAwait(bufferSize)).flatMap { ts =>
              val removeDeferred = ref.update(_._1 -> none)
              Stream.eval(removeDeferred >> newD.complete(())) >> ts.cons(Chunk.chain(buf))
            }
            (buf, Some(newD)) -> next
        })
      }

      def streamOnce: Stream[F, E] = Stream.eval(ref.get.map(_._1)).map(Chunk.chain).flatMap(Stream.chunk)
    }
  }

  implicit def eventLogProfunctor[F[_]]: Profunctor[EventLog[F, *, *]] =
    new Profunctor[EventLog[F, *, *]] {
      override def rmap[A, B, C](io: EventLog[F, A, B])(f: B => C): EventLog[F, A, C] =
        new EventLog[F, A, C] {
          def add(e: A): F[Unit] = io.add(e)
          def streamContinuously: Stream[F, C] = io.streamContinuously.map(f)
          def streamOnce: Stream[F, C] = io.streamOnce.map(f)
        }

      override def lmap[A, B, C](io: EventLog[F, A, B])(f: C => A): EventLog[F, C, B] =
        new EventLog[F, C, B] {
          def add(e: C): F[Unit] = io.add(f(e))
          def streamContinuously: Stream[F, B] = io.streamContinuously
          def streamOnce: Stream[F, B] = io.streamOnce
        }

      def dimap[A, B, C, D](io: EventLog[F, A, B])(f: C => A)(g: B => D): EventLog[F, C, D] =
        new EventLog[F, C, D] {
          def add(e: C): F[Unit] = io.add(f(e))
          def streamContinuously: Stream[F, D] = io.streamContinuously.map(g)
          def streamOnce: Stream[F, D] = io.streamOnce.map(g)
        }

    }
}

/** An `EventLog` where all of your events have a known key type `K`. */
trait KeyedEventLog[F[_], K, In, Out]
    extends KeyedFiniteEventLog[F, K, In, Out]
    with KeyedContinuousEventLog[F, K, In, Out]

object KeyedEventLog {

  /** Creates an in-memory keyed event log that you can subscribe to or stream front-to-back.
    * Useful for testing purposes. Do not use for extended periods of time due to increased memory usage.
    *
    * @param bufferSize The size of the buffer for subscribing to continuous events published. Defaults to 512.
    * @return A `KeyedEventLog` that operates entirely in-memory.
    */
  def inMemory[F[_]: Concurrent, K, E](bufferSize: Int = 512) = EventLog.inMemory[F, E](bufferSize).map { el =>
    new KeyedEventLog[F, K, E, E] {
      def add(e: E): F[Unit] = el.add(e)
      def streamOnce: Stream[F, E] = el.streamOnce
      def streamContinuously: Stream[F, E] = el.streamContinuously
    }
  }

  implicit def keyedEventLogProfunctor[F[_], K]: Profunctor[KeyedEventLog[F, K, *, *]] =
    new Profunctor[KeyedEventLog[F, K, *, *]] {
      override def rmap[A, B, C](io: KeyedEventLog[F, K, A, B])(f: B => C): KeyedEventLog[F, K, A, C] =
        new KeyedEventLog[F, K, A, C] {
          def add(e: A): F[Unit] = io.add(e)
          def streamContinuously: Stream[F, C] = io.streamContinuously.map(f)
          def streamOnce: Stream[F, C] = io.streamOnce.map(f)
          // override key
          // override def streamOnceForKey(key: K)(implicit keyed: Keyed[K, C]): Stream[F, C] =
          //   io.streamOnceForKey(key)(keyed.contramap(f)).map(f)
          // override def streamOnceForKeyBatch(keys: NonEmptySet[K])(implicit keyed: Keyed[K, C]): Stream[F, C] =
          //   io.streamOnceForKeyBatch(keys)(keyed.contramap(f)).map(f)
          // override def streamContinuouslyForKey(key: K)(implicit keyed: Keyed[K, C]): Stream[F, C] =
          //   io.streamContinuouslyForKey(key)(keyed.contramap(f)).map(f)
          // override def streamContinuouslyForKeyBatch(keys: NonEmptySet[K])(implicit keyed: Keyed[K, C]): Stream[F, C] =
          //   io.streamContinuouslyForKeyBatch(keys)(keyed.contramap(f)).map(f)
        }

      override def lmap[A, B, C](io: KeyedEventLog[F, K, A, B])(f: C => A): KeyedEventLog[F, K, C, B] =
        new KeyedEventLog[F, K, C, B] {
          def add(e: C): F[Unit] = io.add(f(e))
          def streamContinuously: Stream[F, B] = io.streamContinuously
          def streamOnce: Stream[F, B] = io.streamOnce
          override def streamOnceForKey(key: K)(implicit keyed: Keyed[K, B]): Stream[F, B] =
            io.streamOnceForKey(key)
          override def streamOnceForKeyBatch(keys: NonEmptySet[K])(implicit keyed: Keyed[K, B]): Stream[F, B] =
            io.streamOnceForKeyBatch(keys)
          override def streamContinuouslyForKey(key: K)(implicit keyed: Keyed[K, B]): Stream[F, B] =
            io.streamContinuouslyForKey(key)
          override def streamContinuouslyForKeyBatch(keys: NonEmptySet[K])(implicit keyed: Keyed[K, B]): Stream[F, B] =
            io.streamContinuouslyForKeyBatch(keys)
        }

      def dimap[A, B, C, D](io: KeyedEventLog[F, K, A, B])(f: C => A)(g: B => D): KeyedEventLog[F, K, C, D] =
        new KeyedEventLog[F, K, C, D] {
          def add(e: C): F[Unit] = io.add(f(e))
          def streamContinuously: Stream[F, D] = io.streamContinuously.map(g)
          def streamOnce: Stream[F, D] = io.streamOnce.map(g)
          override def streamOnceForKey(key: K)(implicit keyed: Keyed[K, D]): Stream[F, D] =
            io.streamOnceForKey(key)(keyed.contramap(g)).map(g)
          override def streamOnceForKeyBatch(keys: NonEmptySet[K])(implicit keyed: Keyed[K, D]): Stream[F, D] =
            io.streamOnceForKeyBatch(keys)(keyed.contramap(g)).map(g)
          override def streamContinuouslyForKey(key: K)(implicit keyed: Keyed[K, D]): Stream[F, D] =
            io.streamContinuouslyForKey(key)(keyed.contramap(g)).map(g)
          override def streamContinuouslyForKeyBatch(keys: NonEmptySet[K])(implicit keyed: Keyed[K, D]): Stream[F, D] =
            io.streamContinuouslyForKeyBatch(keys)(keyed.contramap(g)).map(g)
        }

    }
}
