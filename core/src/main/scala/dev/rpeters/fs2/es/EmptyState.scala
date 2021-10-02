package dev.rpeters.fs2.es

/** Signifies that a state does not exist for some reason. */
sealed trait EmptyState extends Product with Serializable

object EmptyState {

  /** Signifies that a state has just transitioned to being removed. */
  case object Removed extends EmptyState

  /** Signifies that a state was either previously removed or otherwise never initialized. */
  case object NotFound extends EmptyState
}
