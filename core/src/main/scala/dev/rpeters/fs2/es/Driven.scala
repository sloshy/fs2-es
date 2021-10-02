package dev.rpeters.fs2.es

import cats.kernel.Monoid
import cats.syntax.all._
import syntax._

/** Defines a type that can be initialized or modified by incoming events.
  *
  * @param E The type of the events.
  * @param A The type of state the events are applied to.
  */
@annotation.implicitNotFound("""Could not find an implicit Driven[${E}, ${A}] instance
for events of type ${E} applying to state of type ${A}.

Driven is a type class for applying events of some type to a state type. To create one,
look at 'dev.rpeters.fs2.es.Driven.instance' and supply a function for handling events.

If it does not make sense to have optional states for your state type, consider using methods
that require DrivenNonEmpty instead.
""")
trait Driven[E, A] {

  /** Apply an event to a state that may or may not exist.
    *
    * If a state does not exist and it is created, that indicates it is said to have been initialized by some starting event.
    * If a state does exist but `None` is returned after applying this function, that state is said to be removed.
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
  def instance[E, A](f: PartialFunction[(E, Option[A]), Option[A]]) =
    new Driven[E, A] {
      def handleEvent(optA: Option[A])(e: E): Option[A] = f.lift(e -> optA) match {
        case Some(result) => result
        case None         => optA
      }
    }

  /** Create a `Driven` instance derived from your state's `Monoid` instance.
    *
    * In the event that your state is `None`, it is initialized as your type's "empty value",
    * such as 0 for integers or the empty string.
    */
  def monoid[A: Monoid] = new Driven[A, A] {
    def handleEvent(optA: Option[A])(e: A): Option[A] = optA match {
      case Some(value) => Some(value |+| e)
      case None        => Some(Monoid[A].empty |+| e)
    }
  }
}
