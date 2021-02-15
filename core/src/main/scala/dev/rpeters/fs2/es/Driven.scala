package dev.rpeters.fs2.es

import cats.syntax.all._
import syntax._

/** Defines a type that can be initialized or modified by incoming events.
  *
  * @param E The type of the events.
  * @param A The type of state the events are applied to.
  */
trait Driven[E, A] {

  /** Apply an event to a state that may or may not exist.
    *
    * If a state does not exist and it is created, that indicates it is said to have been initialized by some starting event.
    * If a state does exist but `None` is returned after applying this function, that state is said to be deleted.
    *
    * @param optA A possibly-existing state.
    * @param e An event to apply to your state.
    * @return A new state, or no state value, depending on the implementation.
    */
  def handleEvent(optA: Option[A])(e: E): Option[A]
}

object Driven {

  def apply[E, A](implicit instance: Driven[E, A]) = instance

  /** Create a `Driven` instance for applying events to possibly-existing state.
    *
    * If the supplied partial function is not defined for your inputs to `handleEvent` when called,
    * the default behavior is to return the input value.
    *
    * @param f A function to apply events to state.
    * @return An instance of `Driven` for your state type.
    */
  def instance[K, E, A](f: PartialFunction[(E, Option[A]), Option[A]])(implicit keyed: Keyed[K, E]) =
    new Driven[E, A] {
      def handleEvent(optA: Option[A])(e: E): Option[A] = f.lift(e -> optA) match {
        case Some(result) => result
        case None         => optA
      }
    }
}
