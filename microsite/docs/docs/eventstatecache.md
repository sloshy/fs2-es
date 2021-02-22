---
layout: docs
title: EventStateCache
permalink: /docs/eventstatecache/
---
# EventStateCache
With [`EventLog`](eventlog/) we can retrieve all of the keyed states from our event log at any time.
Constantly streaming from the log to retrieve states, however, is not ideal and a waste of resources.
It makes sense that we would want to hold certain states in memory for certain periods of time in the average application, so for that, we have `EventStateCache`.

`EventStateCache` acts as a repository interface for keyed, event-driven state.
It works similarly to a concurrent `Map` with each one of your `EventState`s held behind a key.
What makes `EventStateCache` special is that it understands how to create new states, read them from your event log, and manage their lifetimes for efficiency.

```scala mdoc:compile-only
import dev.rpeters.fs2.es.EmptyState

sealed trait EventStateCache[F[_], K, E, A] {

  /** Access some state by key if it exists in your event log. */
  def use[B](k: K)(f: A => F[B]): F[Option[B]]

  /** Access some state by key if it exists in your event log without caching it. */
  def useDontCache[B](k: K)(f: A => F[B]): F[Option[B]]

  /** Applies an event to your event log, and then to any in-memory states.
   * Does not apply to states not presently in-memory, and does not stream from your event log.
   */
  def addOnlyCached(e: E): F[Option[A]]

  /** Applies an event to your event log, and then to any in-memory states.
    * Will load from the event log and cache the result in memory.
    */
  def addAndCache(e: E): F[Either[EmptyState, A]]

  /** Applies an event to only the event log, deleting any key that might have matched the event from your cache.
    * After calling this, If you try to use state that this event maps to, you will reload state from the event log on the next access.
    * This is to ensure internal consistency and the event log as your source of truth.
    */
  def addQuick(e: E): F[Unit]
}
```

Generally, once you create one, you will want to disregard your underlying `EventLog` as adding events directly to that can result in inconsistent state.
Instead, you will want to use your `EventStateCache` as a write-through cache, as it applies all new events to your event log unconditionally with several different operators for how you want to affect state.
The general case you probably want is `addAndCache`, which caches the resulting state in-memory, but you may find you use less resources in some cases if you use `addQuick` or `addOnlyCached` to bypass loading from the event log.

To access state in the cache, you will want to use `use` which loads state into memory and caches it, or `useDontCache` which loads state but does not cache it.
If state exists in your log or in memory, your function to state will apply.
If it does not exist presently for any reason, you will receive `None`.

## Example
To create an `EventStateCache`, you need an instance of [`KeyedState`](typeclasses/keyedstate/) for your state type.
First, you must define your state's `Driven` instance and your event's `Keyed` instance, like so:
```scala mdoc:silent
import dev.rpeters.fs2.es._

//Our keyed state type
case class User(name: String, points: Int)

//Our event algebra.
sealed trait UserEvent {
  //Every event needs a "key". Ours will be the user's name.
  val name: String
}
case class UserCreated(name: String) extends UserEvent
case class UserPointsChanged(name: String, diff: Int) extends UserEvent

//Define our initial Driven and Keyed instances for our data types
implicit val driven: Driven[UserEvent, User] = Driven.instance {
  case (UserCreated(name), None) =>
    Some(User(name, 0)) //Create a new user with 0 points
  case (UserPointsChanged(_, points), Some(user)) =>
    Some(user.copy(points = user.points + points))
}
implicit val keyed: Keyed[String, UserEvent] = Keyed.instance(_.name)

//Given a Driven/Keyed in scope, derives a KeyedState
implicit val keyedState: KeyedState[String, UserEvent, User] = KeyedState.from(driven, keyed)
```

We can now create an `EventStateCache` as follows:
```scala mdoc:silent
import cats.effect.IO

val getEventLog = EventLog.inMemory[IO, UserEvent]

val getEventStateCache: IO[EventStateCache[IO, String, UserEvent, User]] = getEventLog.flatMap { log =>
  EventStateCache[IO].unbounded[String, UserEvent, User](log) 
}
```

Lets use this as a building block to write a basic event-sourced program:
```scala mdoc:silent
import cats.syntax.all._

val usersToCreate = List("FirstUser", "SecondUser", "ThirdUser").map(UserCreated.apply)

val fullProgram = getEventStateCache.flatMap { cache =>
  
  //First we initialize some new users
  val initializeNewUsers = usersToCreate.traverse(u => cache.addAndCache(UserCreated(u.name)))
  
  //Get a user from state
  def getUser(name: String) = cache.use(name)(IO.pure)

  //We'll create a stream that gives all users 5 points.
  val addToEachUser = usersToCreate.traverse(u => cache.addAndCache(UserPointsChanged(u.name, 5)))

  // Gives us the result of loading in an existing user as well as the result of applying events to all of our new users.
  for {
    _ <- initializeNewUsers
    firstUser <- getUser("FirstUser")
    secondUser <- getUser("SecondUser")
    thirdUser <- getUser("ThirdUser")
    finalStates <- addToEachUser
  } yield (firstUser, secondUser, thirdUser, finalStates)
}
```
```scala mdoc
import cats.effect.unsafe.implicits.global

fullProgram.unsafeRunSync()
```

## Configuration
In the above example, we use an unbounded cache.
There are two primary ways to bound an `EventStateCache` - by time since last use, and total number of states in-memory.
These are available via the `timed` and `bounded` constructors respectively, as well as `timedBounded` for a combination of the two.

You can also supply an "existence check" function to each of these constructors to optimize certain workflows.
If you know for sure that there is a way to prove that state does not exist without touching your event log, you can supply a function of type `K => F[Boolean]` as an optional argument.
By default, this always returns `true`, so if you supply a function that, for some keys, returns `false`, you will skip loading from the event log for those functions.
This can also be useful if you only want to load certain subsets of state in this application.

Just like `EventLog` and `EventState`, you can also transform the inputs and outputs of an `EventStateCache`.
Using the `localizeInput` operator, you can transform the expected event input type, and with `mapState` you can map all state results.
These functions run every time an event is added, or a state is returned, so they do not actually modify the inner workings or data of the cache and event log.
It does, however, allow you to be a little more flexible with how and where you use it.