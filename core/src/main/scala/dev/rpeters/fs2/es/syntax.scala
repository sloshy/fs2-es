package dev.rpeters.fs2.es

object syntax {

  implicit class DrivenInitialOps[A, E](optA: Option[A])(implicit ev: DrivenInitial[E, A]) {

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

  implicit class drivenOps[A, E](a: A)(implicit ev: Driven[E, A]) {

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

  implicit class keyInitialOps[K, A](k: K)(implicit ev: Initial[K, A]) {

    /** Initialize a state value from this key.
      *
      * @return An initialized state value based on this key.
      */
    def initialize: A = ev.initialize(k)
  }

  implicit class modKeyedOps[A, K](a: A)(implicit ev: ModKeyed[K, A]) {

    /** Extract a key from a given value.
      *
      * @return A key value that you extracted.
      */
    def getKey = ev.getKey(a)

    /** Modify the current key with a function.
      *
      * @param f The function applied to your key that may modify it.
      * @return A value with a key that the supplied function might have modified.
      */
    def modKey(f: K => K) = ev.modKey(a)(f)

    /** Set the key contained within a value.
      *
      * @param k The key you wish to set in place of the existing key.
      * @return A value containing the key specified replacing the pre-existing key.
      */
    def setKey(k: K) = ev.setKey(a)(k)
  }

  implicit class keyedOps[K, A](a: A)(implicit ev: Keyed[K, A]) {

    /** Extract a key from a given value.
      *
      * @return A key that you extracted.
      */
    def getKey: K = ev.getKey(a)
  }
}
