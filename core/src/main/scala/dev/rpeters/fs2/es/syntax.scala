package dev.rpeters.fs2.es

import EventState._
import fs2.Stream

object syntax {

  implicit class eventStateOps[F[_], E, A](es: EventState[F, E, A])(implicit
      ops: EventStateLogOps[F, EventState[F, *, *], E, A]
  ) {
    def attachLog(log: EventLog[F, E, _]): EventState[F, E, A] = ops.attachLog(es)(log)
    def attachLogAndApply(log: EventLog[F, E, E]): Stream[F, EventState[F, E, A]] = ops.attachLogAndApply(es)(log)
    def localizeInput[EE](f: EE => E): EventState[F, EE, A] = ops.localizeInput(es)(f)
    def mapState[AA](f: A => AA): EventState[F, E, AA] = ops.mapState(es)(f)
  }

  implicit class eventStateTopicOps[F[_], E, A](es: EventStateTopic[F, E, A])(implicit
      ops: EventStateLogOps[F, EventStateTopic[F, *, *], E, A]
  ) {
    def attachLog(log: EventLog[F, E, _]): EventStateTopic[F, E, A] = ops.attachLog(es)(log)
    def attachLogAndApply(log: EventLog[F, E, E]): Stream[F, EventStateTopic[F, E, A]] = ops.attachLogAndApply(es)(log)
    def localizeInput[EE](f: EE => E): EventStateTopic[F, EE, A] = ops.localizeInput(es)(f)
    def mapState[AA](f: A => AA): EventStateTopic[F, E, AA] = ops.mapState(es)(f)
  }

  implicit class signallingEventStateOps[F[_], E, A](es: SignallingEventState[F, E, A])(implicit
      ops: EventStateLogOps[F, SignallingEventState[F, *, *], E, A]
  ) {
    def attachLog(log: EventLog[F, E, _]): SignallingEventState[F, E, A] = ops.attachLog(es)(log)
    def attachLogAndApply(log: EventLog[F, E, E]): Stream[F, SignallingEventState[F, E, A]] =
      ops.attachLogAndApply(es)(log)
    def localizeInput[EE](f: EE => E): SignallingEventState[F, EE, A] = ops.localizeInput(es)(f)
    def mapState[AA](f: A => AA): SignallingEventState[F, E, AA] = ops.mapState(es)(f)
  }

  implicit class drivenOps[A, E](a: A)(implicit ev: Driven[E, A]) {

    /** Apply an event to this state value,
      *
      * If a state does not exist and it is created, that indicates it was "initialized" by some starting event. If a
      * state does exist but `None` is returned after applying this function, that state was removed/deleted.
      *
      * @param e
      *   An event to apply to your state.
      * @return
      *   The current state, a modified state, or a nonexistent state.
      */
    def handleEvent(e: E): Option[A] = ev.handleEvent(Some(a))(e)
  }

  implicit class drivenOptionOps[A, E](optA: Option[A])(implicit ev: Driven[E, A]) {

    /** Apply an event to this state value that may not exist.
      *
      * If a state does not exist and it is created, that indicates it was "initialized" by some starting event. If a
      * state does exist but `None` is returned after applying this function, that state was removed/deleted.
      *
      * @param e
      *   An event to apply to your optional state.
      * @return
      *   The current state, a modified state, or a nonexistent state.
      */
    def handleEvent(e: E): Option[A] = ev.handleEvent(optA)(e)
  }

  implicit class drivenNonEmptyOps[A, E](a: A)(implicit ev: DrivenNonEmpty[E, A]) {

    /** Apply an event to this state value.
      *
      * If the value is `Some[A]`, the state is either modified or untouched. If the value is `None`, the state is
      * deleted/removed.
      *
      * @param e
      *   An event to apply to your state.
      * @return
      *   The current state, a modified state, or a deleted/removed state.
      */
    def handleEvent(e: E): A = ev.handleEvent(a)(e)
  }

  implicit class keyedOps[K, A](a: A)(implicit ev: Keyed[K, A]) {

    /** Extract a key from a given value.
      *
      * @return
      *   A key that you extracted.
      */
    def getKey: K = ev.getKey(a)
  }
}
