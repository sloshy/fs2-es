package dev.rpeters.fs2.es

import cats.syntax.all._
import syntax._
import cats.data.State

/** Describes some state that can be initialized by a key, and driven by events.
  *
  * @param K The key type you can extract from events.
  * @param E The type of the events.
  * @param A The type of state the events are applied to.
  */
trait KeyedState[K, E, A] extends Driven[E, A] with Initial[K, A] with KeyedStateNonEmpty[K, E, A]

object KeyedState {

  def apply[K, E, A](implicit instance: KeyedState[K, E, A]) = instance

  /** Define how to apply events to an optional value of this type
    *
    * @param canInitialize A function that determines what events can be used to initialize state
    * @param f Given an event and an optional state, apply it to that state.
    * @return An instance of `DrivenInitial` for your state type.
    */
  def instance[K, E, A](implicit driven: Driven[E, A], initial: Initial[K, A], keyed: Keyed[K, E]) =
    new KeyedState[K, E, A] {
      def handleEvent(a: A)(e: E): Option[A] = driven.handleEvent(a)(e)
      def handleEvent(optA: Option[A])(e: E): Option[A] = driven.handleEvent(optA)(e)
      def initialize(k: K): A = initial.initialize(k)
      def getKey(e: E): K = keyed.getKey(e)
    }
}
