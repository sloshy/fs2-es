package dev.rpeters.fs2.es

object syntax {

  implicit class drivenOps[A, E](optA: Option[A])(implicit ev: Driven[E, A]) {

    /** Apply an event to this state value that may not exist.
      *
      * If a state does not exist and it is created, that indicates it was "initialized" by some starting event.
      * If a state does exist but `None` is returned after applying this function, that state was removed/deleted.
      *
      * @param e An event to apply to your optional state.
      * @return The current state, a modified state, or a nonexistent state.
      */
    def handleEvent(e: E): Option[A] = ev.handleEvent(optA)(e)
  }

  implicit class drivenNonEmptyOps[A, E](a: A)(implicit ev: DrivenNonEmpty[E, A]) {

    /** Apply an event to this state value.
      *
      * If the value is `Some[A]`, the state is either modified or untouched.
      * If the value is `None`, the state is deleted/removed.
      *
      * @param e An event to apply to your state.
      * @return The current state, a modified state, or a deleted/removed state.
      */
    def handleEvent(e: E): Option[A] = ev.handleEvent(a)(e)

    /** Apply an event to this state value.
      *
      * Like `handleEvent`, but if applying an event would result in `None`, just return the initial state.
      *
      * @param e An event to apply to your state.
      * @return Either the input state or a modified state.
      */
    def handleEventOrDefault(e: E): A = ev.handleEventOrDefault(a)(e)
  }

  implicit class keyedOps[K, A](a: A)(implicit ev: Keyed[K, A]) {

    /** Extract a key from a given value.
      *
      * @return A key that you extracted.
      */
    def getKey: K = ev.getKey(a)
  }
}
