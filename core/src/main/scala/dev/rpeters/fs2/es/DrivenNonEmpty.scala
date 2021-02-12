package dev.rpeters.fs2.es

import cats.syntax.all._

/** A typeclass for applying events to a known value.
  *
  * This is the weaker sibling of `Driven` that requires a state value to be present.
  *
  * @param E The type of events.
  * @param A The type of state the events are applied to.
  */
trait DrivenNonEmpty[E, A] {

  /** Apply events to a known value of state.
    *
    * If the value is `Some[A]`, the state is either modified or untouched.
    * If the value is `None`, the state is deleted/removed.
    *
    * For initializing states, see `handleEvent` on `DrivenInitial`, which requires extracting a key from an event.
    *
    * @param a Your initial state value you are applying events to.
    * @param e The events being applied to your state.
    * @return An `Option` of your resulting state, whether or not it still exists.
    */
  def handleEvent(a: A)(e: E): Option[A]

  /** Apply an event to a known value of state.
    *
    * Like `handleEvent`, but if an event "deletes" your initial state by returning `None`, resets it to the initial value.
    *
    * For initializing states, see `handleEvent` on `DrivenInitial`, which requires extracting a key from an event.
    *
    * @param a Your initial state value you are applying events to.
    * @param e The events being applied to your state.
    * @return An `Option` of your resulting state, whether or not it still exists.
    */
  def handleEventOrDefault(a: A)(e: E): A = handleEvent(a)(e).getOrElse(a)
}

object DrivenNonEmpty {
  def apply[E, A](implicit instance: DrivenNonEmpty[E, A]) = instance

  /** Define how your state responds to events.
    *
    * @param f A partial function that maps certain events to certain states, and produces new states.
    * @return A new state that might have been derived from the supplied event.
    */
  def instance[E, A](f: PartialFunction[(E, A), Option[A]]) = new DrivenNonEmpty[E, A] {
    def handleEvent(a: A)(e: E): Option[A] = f.lift(e -> a).getOrElse(a.some)
  }
}
