---
layout: docs
title: Testing
permalink: /docs/testing/
---
# Testing
A concept that sometimes appears in event-based programming is the idea of "time-travel debugging", or the ability to go forward and back in time.
Because [`EventState`](eventstate/) enforces a linear, event-driven access pattern, that means that we are able to store all modifications to state and replay them, giving you access to all possible states that have been achieved.
If you install the `fs2-es-testing` module, you'll be able to use `ReplayableEventState` which is an extension of `EventStateTopic` with special testing and debugging methods.

First, add the testing module to your project (available for ScalaJS 1.x as well):
```
libraryDependencies += "dev.rpeters" %% "fs2-es-testing" % <current-version>
```

You can create one the exact same way as other `EventState` implementations, either with an initial value or a stream of events to "hydrate" it with.
```scala mdoc:silent
import cats.effect._
import cats.implicits._
import dev.rpeters.fs2.es.testing.ReplayableEventState

//Creates a new ReplayableEventState on each invocation that adds integers to state
val newState = ReplayableEventState[IO].manualTotal[Int, Int](0)(_ + _)
```

From here, we can start accumulating events as normal, and it will work just like any other `EventStateTopic`.

## Getting The Event List
For testing, you may want to know what the current event list is, so lets accumulate some events and get them back:
```scala mdoc:silent
val eventsTest = for {
  es <- newState //Make the event state
  _ <- es.doNext(1) //Add some events
  _ <- es.doNext(2)
  _ <- es.doNext(3)
  state <- es.get //Check the current state
  events <- es.getEvents //Check the list of events
} yield (state, events)
```
```scala mdoc
import cats.effect.unsafe.implicits.global

eventsTest.flatMap { case (state, events) =>
  IO(println(s"State: $state")) >> IO(println(s"Events: $events"))
}.unsafeRunSync()
```

You may have noticed that the events are returned as a [`Chain`](https://typelevel.org/cats/datatypes/chain.html).
It's a data structure similar to `List`, but optimized for frequent appending.
You can treat it similarly to a `List` or similar traversible data structure, or turn it into one by calling `.toList` as-needed.

If you don't need the entire list of events but you just want the event count, you can call `es.getEventCount`.

## Seeking By Index
Sometimes when debugging you might want to go "backwards" to a previous state.
You can seek backwards by specifying the index of the state you would like to go to, or optionally specifying an offset to seek forwards and backwards.

The available methods for this are:
* `seekTo(n)` - Seek to index `n`
* `seekToBeginning` - Alias for `seekTo(0)`
* `seekBackBy(n)` - Goes back `n` states ago.
* `seekForwardBy(n)` - Goes forward `n` states ahead of the current state.

Seeking is a **non-destructive action** which means you can do it safely without destroying the current event history.
If you do append a new event to the current state, it will drop all later events (if any), so be sure to save them if you want to replay them later.

```scala mdoc:silent
val seekTest = for {
  es <- newState //Make the event state
  _ <- es.doNext(1) //Add some events
  _ <- es.doNext(2)
  _ <- es.doNext(3)
  oldState <- es.get //Check the current state
  oldEvents <- es.getEvents //Check the list of events
  newState <- es.seekTo(1) //Go to the second state, after applying the first event (1)
  sameEvents <- es.getEvents //Get the event list, to show it is non-destructive
} yield (oldState, oldEvents, newState, sameEvents)
```
```scala mdoc
seekTest.flatMap { case (oldState, events, newState, sameEvents) =>
  IO(println(s"Old state: $oldState")) >>
    IO(println(s"Old Events: $events")) >>
    IO(println(s"New state: $newState")) >>
    IO(println(s"Same Events: $sameEvents"))
}.unsafeRunSync()
```

## Resetting state
There are special `reset` and `resetInitial` methods now available that allow you to completely wipe the current state including the list of events.
Calling `reset` allows you to go back to the first accumulated state, while `resetInitial` allows you to provide a new initial state to reset to.

```scala mdoc:silent
val resetTest = for {
  es <- newState //Make the event state
  _ <- es.doNext(1) //Add some events
  _ <- es.doNext(2)
  _ <- es.doNext(3)
  latestState <- es.get //Check the current state
  resettedState <- es.reset //Reset to zero
  resettedEvents <- es.getEvents //Get events, to show it is cleared
  newInitialState <- es.resetInitial(5) //Set the first state to 5
  newInitialEvents <- es.getEvents //Check events again, which should still be empty
} yield (latestState, resettedState, resettedEvents, newInitialState, newInitialEvents)
```
```scala mdoc
resetTest.flatMap { case (ls, rs, re, nis, nie) =>
  IO(println(s"Latest State: $ls")) >>
    IO(println(s"Resetted State: $rs")) >>
    IO(println(s"Resetted Events: $re")) >>
    IO(println(s"New Initial State: $nis")) >>
    IO(println(s"New Initial Events: $nie"))
}.unsafeRunSync()
```

Each time you seek or reset state, that new state is also sent to all subscribers.
This is to ensure that reactive applications, such as React apps in Scala.JS, will re-render your changes to state as soon as they happen.
Please be aware of this fact when debugging your code as resetting state might trigger unintended actions if you are not careful.
