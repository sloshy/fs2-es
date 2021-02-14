package dev.rpeters.fs2.es

import cats.syntax.all._
import syntax._

/** Defines a type that can be initialized or modified by incoming events.
  *
  * @param E The type of the events.
  * @param A The type of state the events are applied to.
  */
trait Driven[E, A] extends DrivenNonEmpty[E, A] {

  /** Defines how you apply an event `E` to a value `A` that may or may not exist.
    *
    * If a state does not exist and it is created, that indicates it was "initialized" by some starting event.
    * If a state does exist but `None` is returned after applying this function, that state was removed/deleted.
    *
    * @param optA An optional value representing a possibly-existing state to modify with events.
    * @param e An event to apply to your optional state.
    * @return A new optional value that might be modified as a result of your event.
    */
  def handleEvent(optA: Option[A])(e: E): Option[A]
}

object Driven {

  def apply[E, A](implicit instance: Driven[E, A]) = instance

  /** Define how to apply events to an optional value of this type
    *
    * @param f Given an event and an optional state, apply it to that state.
    * @return An instance of `DrivenInitial` for your state type.
    */
  def instance[K, E, A](f: PartialFunction[(E, Option[A]), Option[A]])(implicit keyed: Keyed[K, E]) =
    new Driven[E, A] {
      def handleEvent(optA: Option[A])(e: E): Option[A] = f.lift(e -> optA).flatten
      def handleEvent(a: A)(e: E): Option[A] = handleEvent(a.some)(e)
    }
}
