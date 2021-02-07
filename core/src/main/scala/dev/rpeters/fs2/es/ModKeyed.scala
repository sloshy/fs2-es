package dev.rpeters.fs2.es

/** A typeclass defining the ability to extract and set a "key" from some value.
  * A weaker version of `InitialKeyed` that just implies the ability to extract or set a new key.
  *
  * To be lawful, the following must be true:
  * 1. `getKey(a) == getKey(a)` for all `a`.
  * 2. `setKey(a)(getKey(a))` should be idempotent.
  * 3. `getKey(modKey(a)(f))` should equal `f(getKey(a))` in cases where `f(getKey(a)) != getKey(a)`.
  */
trait ModKeyed[K, A] extends Keyed[K, A] {

  /** Modify the current key with a function.
    *
    * @param a The value whose key is being modified.
    * @param f The function applied to your key that may modify it.
    * @return A value with a key that the supplied function might have modified.
    */
  def modKey(a: A)(f: K => K): A = setKey(a)(f(getKey(a)))

  /** Set the key contained within a value.
    *
    * @param a The value containing a key.
    * @param k The key you wish to set in place of the existing key.
    * @return A value containing the key specified replacing the pre-existing key.
    */
  def setKey(a: A)(k: K): A
}

object ModKeyed {
  def apply[K, A](implicit instance: ModKeyed[K, A]) = instance

  /** Define the relationship between your type `A` and its key `K`
    *
    * @param f Derive a key `K` from an arbitrary `A` value.
    * @return An instance of `ModKeyed[K, A]`
    */
  def instance[K, A](getF: A => K)(setF: (A, K) => A) = new ModKeyed[K, A] {
    def getKey(a: A): K = getF(a)
    def setKey(a: A)(k: K): A = setF(a, k)
  }

  implicit class modKeyedOps[A, K](a: A)(implicit ev: ModKeyed[K, A]) {

    /** Extract a key from a given value.
      *
      * @return A key value that you extracted.
      */
    def getKey = ev.getKey(a)

    /** Modify the current key with a function.
      *
      * @param f The function applied to your key that may modify it.
      * @return A value with a key that the supplied function might have modified.
      */
    def modKey(f: K => K) = ev.modKey(a)(f)

    /** Set the key contained within a value.
      *
      * @param k The key you wish to set in place of the existing key.
      * @return A value containing the key specified replacing the pre-existing key.
      */
    def setKey(k: K) = ev.setKey(a)(k)
  }
}
