package dev.rpeters.fs2.es

import cats.Contravariant

/** A type class defining the ability to extract a "key" from some value, where a "key" is some data you can derive from
  * it. A weaker version of `Initial` that only specifies the ability to extract a key, with no initialization.
  *
  * To be lawful, you only have to assert that `getKey(a) == getKey(a)` for all `a`.
  *
  * @param K
  *   The type of key.
  * @param A
  *   The value that contains said key.
  */
@annotation.implicitNotFound(
  """Could not find an implicit Keyed[${K}, ${A}] instance for keys of type ${K} and values of type ${A}.

Keyed is a type class for extracting a "key" from some value, usually events. To create one,
look at 'dev.rpeters.fs2.es.Keyed.instance' and supply a function for extracting a key.
"""
)
trait Keyed[K, -A] {

  /** Extract a key from a given value.
    *
    * @param a
    *   The value that contains a key.
    * @return
    *   A key that you extracted.
    */
  def getKey(a: A): K
}

object Keyed {
  def apply[K, A](implicit instance: Keyed[K, A]) = instance

  /** Define how to extract a key from some value.
    *
    * @param f
    *   A function to extract the type of key from your value.
    * @return
    *   A `Keyed` instance for your value for extracting keys.
    */
  def instance[K, A](f: A => K) = new Keyed[K, A] {
    def getKey(a: A): K = f(a)
  }

  implicit def keyedContravariant[K]: Contravariant[Keyed[K, *]] = new Contravariant[Keyed[K, *]] {
    def contramap[A, B](fa: Keyed[K, A])(f: B => A): Keyed[K, B] = new Keyed[K, B] {
      def getKey(a: B): K = fa.getKey(f(a))
    }
  }
}
