package dev.rpeters.fs2.es

import cats.syntax.all._
import syntax._
import cats.data.State

/** Describes some state that is driven by events with a known key.
  *
  * This is the sibling of `KeyedState` that only allows applying events to known state values.
  *
  * @param K The key type you can extract from events.
  * @param E The type of the events.
  * @param A The type of state the events are applied to.
  */
@annotation.implicitNotFound("""Could not find an implicit KeyedStateNonEmpty[${K}, ${E}, ${A}] instance
for keys of type ${K}, events of type ${E}, and state of type ${A}.

KeyedStateNonEmpty is a type class for state values that are driven by events that contain keys.
This is used for some functionality that depends on the ability to filter events by some property.

To create one, make sure you have an implicit DrivenNonEmpty[${E}, ${A}] and Keyed[${K}, ${E}] in-scope,
and then define one as follows, preferably in a companion object to your state like so:

object ${A} {
  implicit val keyedState: KeyedStateNonEmpty[${K}, ${E}, ${A}] = KeyedStateNonEmpty.instance
}
""")
trait KeyedStateNonEmpty[K, E, A] extends DrivenNonEmpty[E, A] with Keyed[K, E]

object KeyedStateNonEmpty {

  def apply[K, E, A](implicit instance: KeyedStateNonEmpty[K, E, A]) = instance

  /** Define how to apply events to a state value.
    * Alias for `instance` but without implicits.
    *
    * @param canInitialize A function that determines what events can be used to initialize state
    * @param f Given an event and an optional state, apply it to that state.
    * @return An instance of `DrivenInitial` for your state type.
    */
  def from[K, E, A](driven: DrivenNonEmpty[E, A], keyed: Keyed[K, E]) = instance(driven, keyed)

  /** Define how to apply events to a state value.
    *
    * @param canInitialize A function that determines what events can be used to initialize state
    * @param f Given an event and an optional state, apply it to that state.
    * @return An instance of `DrivenInitial` for your state type.
    */
  def instance[K, E, A](implicit driven: DrivenNonEmpty[E, A], keyed: Keyed[K, E]) =
    new KeyedStateNonEmpty[K, E, A] {
      def handleEvent(a: A)(e: E): A = driven.handleEvent(a)(e)
      def getKey(e: E): K = keyed.getKey(e)
    }
}
