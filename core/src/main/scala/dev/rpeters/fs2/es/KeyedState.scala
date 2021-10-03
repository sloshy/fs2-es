package dev.rpeters.fs2.es

import cats.syntax.all._
import syntax._

/** Describes some state that can be initialized by a key, and driven by events.
  *
  * @param K
  *   The key type you can extract from events.
  * @param E
  *   The type of the events.
  * @param A
  *   The type of state the events are applied to.
  */
@annotation.implicitNotFound("""Could not find an implicit KeyedState[${K}, ${E}, ${A}] instance
for keys of type ${K}, events of type ${E}, and state of type ${A}.

KeyedState is a type class for state values that are driven by events that contain keys.
This is used for some functionality that depends on the ability to filter events by some property.

To create one, make sure you have an implicit Driven[${E}, ${A}] and Keyed[${K}, ${E}] in-scope,
and then define one as follows, preferably in a companion object to your state like so:

object ${A} {
  implicit val keyedState: KeyedState[${K}, ${E}, ${A}] = KeyedState.instance
}
""")
trait KeyedState[K, E, A] extends Driven[E, A] with Keyed[K, E]

object KeyedState {

  def apply[K, E, A](implicit instance: KeyedState[K, E, A]) = instance

  /** Define this type `A` as driven by events `E` with keys `K` in each event. Alias for `instance` but without
    * implicits.
    *
    * @return
    *   An instance of `KeyedState` for your state type.
    */
  def from[K, E, A](driven: Driven[E, A], keyed: Keyed[K, E]) = instance(driven, keyed)

  /** Define this type `A` as driven by events `E` with keys `K` in each event.
    *
    * @return
    *   An instance of `KeyedState` for your state type.
    */
  def instance[K, E, A](implicit driven: Driven[E, A], keyed: Keyed[K, E]) =
    new KeyedState[K, E, A] {
      def handleEvent(optA: Option[A])(e: E): Option[A] = driven.handleEvent(optA)(e)
      def getKey(e: E): K = keyed.getKey(e)
    }
}
