package dev.rpeters.fs2.es

/** A typeclass defining a way to initialize certain values with a "key", as well as modify and extract that "key".
  *
  * This is different to an isomorphism, where the two types can be converted between with no loss of detail.
  *
  * To be lawful, you must assert the following things:
  *
  * 1. `getKey(initialize(k)) == k` for all `k`.
  * 2. `getKey(a) == getKey(a)` for all `a`.
  * 3. `setKey(a)(getKey(a))` should be idempotent.
  * 4. `getKey(modKey(a)(f))` should equal `f(getKey(a))` in cases where `f(getKey(a)) != getKey(a)`.\
  * 5. `getKey(setKey(initialize(k1))(k2)) != k1` and `getKey(setKey(initialize(k1))(k2)) == k2` when `k1 != k2`.
  */
trait Initial[K, A] extends ModKeyed[K, A] {

  /** Initialize a value from a given key.
    *
    * @param k Your key that you are initializing a value with.
    * @return The initialized value.
    */
  def initialize(k: K): A
}

object Initial {

  def apply[A, B](implicit instance: Initial[A, B]) = instance

  /** Define the relationship between your type `A` and `B`.a
    *
    * @param initialF A function to initialize an `A` value from a key `K`.
    * @param getKeyF A function to extract the key `K` from an `A` value.
    * @return An `Initialize` instance representing the ability to initialize `A` values with `K`.
    */
  def instance[K, A](initialF: K => A)(getF: A => K)(setF: (A, K) => A) = new Initial[K, A] {
    def initialize(k: K): A = initialF(k)
    def getKey(a: A): K = getF(a)
    def setKey(a: A)(k: K) = setF(a, k)
  }
}
