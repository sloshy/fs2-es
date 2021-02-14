package dev.rpeters.fs2.es

/** Signifies that a state does not exist. */
sealed trait EmptyState extends Product with Serializable

object EmptyState {

  /** Signifies that a state has just transitioned to being deleted. */
  case object Deleted extends EmptyState

  /** Signifies that a state was either previously deleted or otherwise never initialized. */
  case object NotFound extends EmptyState
}
