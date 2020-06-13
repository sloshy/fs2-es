# FS2-ES
![Sonatype Nexus (Releases)](https://img.shields.io/nexus/r/dev.rpeters/fs2-es_2.13?label=latest&server=https%3A%2F%2Foss.sonatype.org) [![Gitter](https://badges.gitter.im/fs2-es/community.svg)](https://gitter.im/fs2-es/community?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge) [![javadoc](https://javadoc.io/badge2/dev.rpeters/fs2-es_2.13/javadoc.svg)](https://javadoc.io/doc/dev.rpeters/fs2-es_2.13/latest/dev/rpeters/fs2/es/index.html)

This is a small library to encode event-sourcing patterns using FS2, a streaming library in Scala.
The library is polymorphic using Cats Effect, so you can use it with any effect type you want that implements `cats.effect.Concurrent`.

**This library is VERY much a work in progress - use at your own risk.**

To use, add the library to your `build.sbt` like so:
```
libraryDependencies += "dev.rpeters" %% "fs2-es" % "<latest-version>"
```

Currently Scala 2.12 and 2.13 are both supported.

# Introduction
Event sourcing is an age-old concept about how you model state in your applications.
To put it simply, all state is modeled as a left fold on a linear sequence of "events".
For example, here is an extremely basic "event-sourced" program using FS2:

```scala
import cats.implicits._
import fs2.{Pipe, Pure, Stream}

def buildState: Pipe[Pure, Int, Int] = s => s.fold(0)(_ + _)

val incomingEvents = Stream(1, 2, 3)
// incomingEvents: Stream[Nothing, Int] = Stream(..)

val finalState = incomingEvents.through(buildState).compile.last
// finalState: cats.package.Id[Option[Int]] = Some(6)
```

There are several advantages to building your state from events, especially if they hold the following properties:
* Events are immutable and never change
* Events represent things that have happened, and not intentions to perform a specific action
* The order of events is strictly linear for any "aggregate root" (that is, a single unit of state that does not depend on any parent relationship).

In trying to achieve these properties, certain patterns emerge that this library hopes to properly encode.
I personally take the view that overly-opinionated frameworks around event sourcing are a bad idea as they not only constrain the entire design of your progam but they also make it harder to be more flexible with the definition of "event sourcing" that you happen to employ.
For example, many frameworks make an opinionated decision about where you store your linear, immutable event log.
This library has nothing to say about persistence, only functionality related to restoring and managing the lifetimes of state from events.
You can very easily build your own event log just by serializing events and putting them in a database table, Apache Kafka or Pulsar, or even to a raw file for example, and in my opinion that is the easiest part of this to "get right" on your own.

This library chooses to focus on some of the more easily composable parts of event sourcing.
To that end, it comes with a few useful utilities you should get to know:

## EventState

An `EventState` is a common abstraction to help you manage best practices for dealing with event-sourced state.
It can only be created with an initial value, and optionally a stream of events to "rehydrate" it by folding over them, just like in the opening example.

```scala
import cats.effect._
import dev.rpeters.fs2.es.EventState

val initialEventState = for {
  es <- EventState[IO].initial[Int, Int](1)(_ + _)
  _ <- es.doNext(1)
  result <- es.get
} yield result
// initialEventState: IO[Int] = Bind(
//   Map(
//     Delay(cats.effect.concurrent.Ref$$$Lambda$8776/392235525@1beb98fe),
//     dev.rpeters.fs2.es.EventState$EventStatePartiallyApplied$$Lambda$8777/126945862@3674ed33,
//     0
//   ),
//   <function1>
// )

initialEventState.unsafeRunSync()
// res0: Int = 2

val hydratedEventState = EventState[IO].hydrated[Int, Int](1, Stream.emit(1))(_ + _).flatMap(es => es.get)
// hydratedEventState: IO[Int] = Bind(
//   Bind(
//     Delay(cats.effect.concurrent.Ref$$$Lambda$8776/392235525@46ac050d),
//     dev.rpeters.fs2.es.EventState$EventStatePartiallyApplied$$Lambda$8780/1085231442@5669b5f2
//   ),
//   <function1>
// )

hydratedEventState.unsafeRunSync()
// res1: Int = 2
```

The only way to change a value in an `EventState` is to supply it manually to `doNext` or otherwise have it part of the initial hydrating stream.
It is basically just a small wrapper around a `cats.effect.concurrent.Ref` that enforces an event-based access pattern.

You can also "hook up" a stream of events to an `EventState` to get a stream of the resulting states back:

```scala
val hookedUpStream = EventState[IO].initial[Int, Int](1)(_ + _).flatMap { es =>
  Stream(1, 1, 1).through(es.hookup).compile.toList
}
// hookedUpStream: IO[List[Int]] = Bind(
//   Map(
//     Delay(cats.effect.concurrent.Ref$$$Lambda$8776/392235525@5cf54aae),
//     dev.rpeters.fs2.es.EventState$EventStatePartiallyApplied$$Lambda$8777/126945862@642a8593,
//     0
//   ),
//   <function1>
// )

hookedUpStream.unsafeRunSync()
// res2: List[Int] = List(2, 3, 4)
```

When using `hookup`, if you only have a single event stream going into your `EventState` then the resulting stream is guaranteed to have all possible state changes.
If you have more relaxed constraints, look into using `SignallingEventState` instead with the `EventState.signalling` builder.
It has methods `continuous` and `discrete` that mirror those on `fs2.concurrent.SignallingRef`.
These will let you get a continuous stream of the current state or a stream of changes as-detected, but neither is guaranteed to give you all changes in state.

## EphemeralResource
Not directly related to events, but a useful primitive nonetheless, an `EphemeralResource` is a concurrently available value that expires after a certain period of time.
When using event sourcing in particular, it can be helpful to "cache" event state in memory so that your application is not continuously reading from the event log every time it needs the latest state for something.
This abstraction uses an internal timer that resets after each use so that lifetime management of your state is automated.

Here is a simple example:
```scala
import dev.rpeters.fs2.es.data.EphemeralResource
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._

implicit val cs = IO.contextShift(global)
// cs: ContextShift[IO] = cats.effect.internals.IOContextShift@6a49ce3
implicit val timer = IO.timer(global)
// timer: Timer[IO] = cats.effect.internals.IOTimer@3bbdb1d0

val timedResource = for {
  res <- EphemeralResource[IO].timed(1, 2.seconds)
  firstResult <- res.use(i => IO.pure(i + 1))
  _ <- res.expired
  secondResult <- res.use(i => IO.pure(i + 2))
} yield (firstResult, secondResult)
// timedResource: IO[(Option[Int], Option[Int])] = Bind(
//   Bind(
//     Bind(
//       Bind(
//         Delay(cats.effect.concurrent.Deferred$$$Lambda$8808/126161124@1b409505),
//         io.chrisdavenport.agitation.Agitation$$$Lambda$8809/982572508@5d27cfcd
//       ),
//       cats.FlatMap$$Lambda$8811/1635487103@7f46030e
//     ),
//     dev.rpeters.fs2.es.data.EphemeralResource$EphemeralResourcePartiallyApplied$$Lambda$8812/905127902@29a30e07
//   ),
//   <function1>
// )

timedResource.unsafeRunSync
// res3: (Option[Int], Option[Int]) = (Some(2), None)
```

There is also a variant `EphemeralResource[F].uses` that lets you specify a maximum number of uses, but I personally find the timed variant to be more practical for event sourcing.

n.b. Despite the name and `use` method semantics, this type has nothing in common with `cats.effect.Resource`.

## EventStateCache
Now that we have abstractions for both event-sourced state and timed lifetime management, we can put the two together and automatically manage the lifetimes of `EventState` with `EventStateCache`.

`EventStateCache` acts as a repository interface for generic event-sourced state.
It works similarly to a concurrent `Map` with each one of your `EventState`s held behind a key.
What makes `EventStateCache` special is that it understands how to create new states, read them from your event log, and manage their lifetimes for efficiency.

To create an `EventStateCache`, you need several functions and values defined that you plug into it.
Here are all of the parameters necessary, with description:
```scala
import cats.Applicative

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
```scala
import dev.rpeters.fs2.es.EventStateCache

val cacheF = EventStateCache[IO].rehydrating(initializer)(keyHydrator[IO])(eventProcessor)(ttl, existenceCheck[IO])
// cacheF: IO[AnyRef with EventStateCache[IO, String, Int, User]] = Bind(
//   Map(
//     Delay(cats.effect.concurrent.Ref$$$Lambda$8776/392235525@55eeb63b),
//     dev.rpeters.fs2.es.data.MapRef$MapRefPartiallyApplied$$Lambda$8872/2022707004@1aff91fb,
//     0
//   ),
//   dev.rpeters.fs2.es.EventStateCache$EventStateCachePartiallyApplied$$Lambda$8873/1174270933@2b9d7795
// )
```

Lets use this as a building block to write a basic event-sourced program:

```scala
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
```scala
fullProgram.unsafeRunSync()
// res4: (Option[User], List[(String, Option[User])]) = (
//   Some(User("ExistingUser", 3)),
//   List(("FirstUser", None), ("SecondUser", None), ("ThirdUser", None))
// )
```

As you would expect, these states in memory are only kept for the specified duration of 2 minutes.
While not shown here, you can try it yourself or look in the library tests for examples.

### Addendum: MapRef
Not directly part of the API but made public for the current release anyway, `MapRef` is used internally as a small wrapper around an immutable `Map` inside of a `cats.effect.concurrent.Ref`.
Feel free to use it in your own projects, or as part of your own codebase, if you find it necessary.

### Addendum: DeferredMap
Built on top of MapRef, this represents a map of awaitable values.
That is to say, given some `MapRef[F, K, V]`, its equivalent `DeferredMap` has every `V` wrapped in a `cats.effect.concurrent.Deferred` internally.
What this means is that it is essentially a keyed `Deferred` where each key is potentially a new value.
This is used internally by `EventStateCache` so that if you get multiple requests for rehydrating state, you will run the rehydrating function at max once.
`DeferredMap` has a large host of useful methods that will allow you to build concurrent programs that await values by key.
Feel free to use it in your own projects, and any bug reports are much appreciated here.

## What to use?
Now that we've gone through the library at large, there remains the question of exactly how much of this you need.
If you are doing a small event-sourced program and maybe only have a few, finite sources of event-sourced state, you can get by with only `EventState` just fine.
If you have a number that you are quite confident should fit in memory, but might be dynamic for other reasons, make a `MapRef[K, EventState]` or use some other pattern/structure to organize your state.
If you need custom lifetime management built on top of that, feel free to write your own structures using `EphemeralResource` as well on top of that, or on the side as-needed.
Lastly, if you need all of that plus a key/value repository interface for your event-sourced state, `EventStateCache` should give you everything you need at once.
It not only handles retrieving your state from your event log as you define it, but it also makes sure that you do not waste precious time or resources re-running the same event log queries by caching state in-memory.

I wrote this library with composition in mind, so if you do not need "the full package" you should very easily be able to build what you need with each of the smaller parts that make up one `EventStateCache`.
The last thing I want is to say "this is how you write an event-sourced application using FS2", as that kind of cargo-culting will only lead to poor quality software.
So try it out, see what works for you, and if you were able to build something that fit your use cases better with it, be sure to let me know!

Happy event sourcing!