package dev.rpeters.fs2.es

package dev.rpeters.fs2.es

import cats.Functor

/** Defines a type that can be initialized or modified by incoming events.
  *
  * @param E The type of the events.
  * @param A The type of state you are working with.
  */
trait Evented[E, A] {

  /** Defines how you apply an event `E` to a value `A` that may or may not exist.
    *
    * The input value
    *
    * @param optA An optional value representing a possibly-existing state to modify with events.
    * @param e An event to apply to your optional state.
    * @return A new optional value that might be modified as a result of your event.
    */
  def handleEvent(optA: Option[A])(e: E): Option[A]
}

object Evented {

  def apply[E, A](implicit instance: Evented[E, A]) = instance

  /** Define how to apply events to an optional value of this type
    *
    * @param f Given an event and an optional state, apply it to that state.
    * @return An instance of `Evented` for your state type.
    */
  def instance[E, A](f: (E, Option[A]) => Option[A]) =
    new Evented[E, A] {
      def handleEvent(optA: Option[A])(e: E): Option[A] = f(e, optA)
    }

  implicit class eventedOps[A, E](optA: Option[A])(implicit ev: Evented[E, A]) {

    /** Defines how you apply an event `E` to a value that may or may not exist.
      *
      * @param e An event to apply to your optional state.
      * @return A new optional value that might be modified from an event.
      */
    def handleEvent(e: E): Option[A] = ev.handleEvent(optA)(e)
  }
}
