package dev.rpeters.fs2.es

import cats.kernel.Monoid
import cats.syntax.all._

/** A typeclass for applying events to a known value.
  *
  * This is the sibling of `Driven` that requires a state value to be present for all events.
  *
  * @param E The type of events.
  * @param A The type of state the events are applied to.
  */
trait DrivenNonEmpty[E, A] {

  /** Apply an event to a known value of state.
    *
    * @param a Your current state value.
    * @param e The event to apply to your state.
    * @return A resulting state, or the input value, depending on implementation.
    */
  def handleEvent(a: A)(e: E): A
}

object DrivenNonEmpty {
  def apply[E, A](implicit instance: DrivenNonEmpty[E, A]) = instance

  /** Create a `DrivenNonEmpty` instance for applying events to state.
    *
    * @param f A function that applies events to state.
    * @return An instance of `DrivenNonEmpty` for your state type.
    */
  def instance[E, A](f: (E, A) => A) = new DrivenNonEmpty[E, A] {
    def handleEvent(a: A)(e: E): A = f(e, a)
  }

  /** Create a `DrivenNonEmpty` instance powered by your state's `Monoid` instance. */
  def monoid[A](implicit m: Monoid[A]) = new DrivenNonEmpty[A, A] {
    def handleEvent(a: A)(e: A): A = m.combine(a, e)
  }
}
