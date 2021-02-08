---
layout: docs
title: EventStateCache
permalink: /docs/eventstatecache/
---
# EventStateCache
Now that we have abstractions for both event-sourced state and timed lifetime management, we can put the two together and automatically manage the lifetimes of `EventState` with `EventStateCache`.

`EventStateCache` acts as a repository interface for generic event-sourced state.
It works similarly to a concurrent `Map` with each one of your `EventState`s held behind a key.
What makes `EventStateCache` special is that it understands how to create new states, read them from your event log, and manage their lifetimes for efficiency.

To create an `EventStateCache`, you need several functions and values defined that you plug into it.
Here are all of the parameters necessary, with description:
```scala mdoc:silent
import cats.Applicative
import cats.effect.IO
import dev.rpeters.fs2.es.EventStateCache
import fs2.Stream
import scala.concurrent.duration._

// Our event-sourced state. Each user has a name and a point value.
// We will be incrementing the user's points through events keyed to that user.
case class User(name: String, points: Int)

def fakeEventLog[F[_]] = Stream[F, Int](1, 1, 1)

// Function #1 - Defines how you create an "initial state" given a key.
// Don't worry about data that is not contained within the key at this stage.
// Those should be modifiable as events - remember, every single change to state should be an event.
def initializer(k: String): User = User(k, 0)

// Function #2 - Defines how you restore state by reading in events by-key.
// In a real application this will likely be a query or reading from a file/stream/topic and filtering by key.
def keyHydrator[F[_]](k: String): Stream[F, Int] = if (k == "ExistingUser") fakeEventLog[F] else Stream.empty

// Function #3 - Defines how you apply event to state.
// This is exactly the same as the function used when creating an `EventState` manually.
def eventProcessor(event: Int, state: User): User = state.copy(points = state.points + event)

// Function #4 - An optional function to check that state for a given key already exists in your event log.
// By default, this function is defined as testing that your `keyHydrator` function returns at least one event.
// If you define this function, you can provide a more optimized way to check that a key already exists in your event log.
// You can also disable the functionality entirely by returning `false`.
def existenceCheck[F[_]: Applicative](k: String): F[Boolean] = if (k == "ExistingUser") Applicative[F].pure(true) else Applicative[F].pure(false)

// Lastly we need a time-to-live duration for all states.
val ttl = 2.minutes
```

Finally, we can create an `EventStateCache` as follows:
```scala mdoc:invisible
import cats.effect.{ContextShift, Timer}
implicit val cs: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)
```
```scala mdoc:silent
val cacheF: IO[EventStateCache[IO, String, Int, User]] = 
  EventStateCache[IO].rehydrating(initializer)(keyHydrator[IO])(eventProcessor)(ttl, existenceCheck[IO])
```

Lets use this as a building block to write a basic event-sourced program:
```scala mdoc:silent
import fs2.Pure

// An event type we can use to help initialize state for users.
case class UserCreatedEvent(name: String)

val usersToCreate: Stream[Pure, UserCreatedEvent] = Stream("FirstUser", "SecondUser", "ThirdUser").map(UserCreatedEvent)

val fullProgram = cacheF.flatMap { cache =>
  
  // Because our existence check will fail for these, it should initialize these three with 0 points.
  val initializeNewUsers = usersToCreate.evalTap(u => cache.add(u.name)).compile.drain
  
  // Our hydrate function will be used when we call `.use` on our cache.
  val getExistingUser = cache.use("ExistingUser")(es => es.get)

  // We'll create a stream that gives all users 5 points.
  // `hookup` is a `Pipe` that passes our events through to the underlying `EventState` by-key.
  // Also see: `hookupKey` for a key-specific pipe.
  val pointsByKey = usersToCreate.map(k => k.name -> 5)
  val addToEachUser = pointsByKey.through(cache.hookup).compile.toList

  // Gives us the result of loading in an existing user as well as the result of applying events to all of our new users.
  for {
    _ <- initializeNewUsers
    existing <- getExistingUser
    list <- addToEachUser
  } yield (existing, list)
}
```
```scala mdoc
fullProgram.unsafeRunSync()
```

As you would expect, these states in memory are only kept for the specified duration of 2 minutes.
After it has been 2 minutes since the last usage, it will try your "hydrator" function to retrieve a stream of events to recreate the current state for your entity.
Be sure, then, to have some kind of store for your events as they come in so that they can be properly retrieved.