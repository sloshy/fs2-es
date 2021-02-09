package dev.rpeters.fs2.es

/** A typeclass defining the ability to extract a "key" from some value, where a "key" is some data you can derive from it.
  * A weaker version of `Initial` that only specifies the ability to extract a key, with no initialization.
  *
  * To be lawful, you only have to assert that `getKey(a) == getKey(a)` for all `a`.
  */
trait Keyed[K, A] {

  /** Extract a key from a given value.
    *
    * @param a The value that contains a key.
    * @return A key that you extracted.
    */
  def getKey(a: A): K
}

object Keyed {
  def apply[K, A](implicit instance: Keyed[K, A]) = instance

  /** Define how to extract a key from some value.
    *
    * @param f A function to extract the type of key from your value.
    * @return A `Keyed` instance for your value for extracting keys.
    */
  def instance[K, A](f: A => K) = new Keyed[K, A] {
    def getKey(a: A): K = f(a)
  }
}
