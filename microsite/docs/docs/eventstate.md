---
layout: docs
title: EventState
permalink: /docs/eventstate/
---
# EventState

An `EventState` is a common abstraction to help you manage best practices for dealing with event-sourced state.
It can only be created with an initial value, and optionally a stream of events to "rehydrate" it by folding over them.

Example for only initial state:
```scala mdoc:silent
import cats.effect._
import dev.rpeters.fs2.es.EventState

val initialEventState = for {
  es <- EventState[IO].initial[Int, Int](1)(_ + _)
  _ <- es.doNext(1)
  result <- es.get
} yield result
```
```scala mdoc
initialEventState.unsafeRunSync()
```

Example for rehydrating state:
```scala mdoc:silent
import fs2.Stream

val eventState = EventState[IO].initial[Int, Int](1)(_ + _)
val hydratedState: IO[Int] = for {
  es <- eventState
  _ <- es.hydrate(Stream.emit(1))
  result <- es.get
} yield result
```
```scala mdoc
hydratedState.unsafeRunSync()
```

The only way to change a value in an `EventState` is to supply it manually to `doNext` or supply a hydrating stream of events.
In this way, an `EventState` is basically just a small wrapper around a `cats.effect.concurrent.Ref` that enforces an event-based access pattern.

You can also "hook up" a stream of events to an `EventState` to hydrate it and get a stream of the resulting states back:

```scala mdoc:silent
val hookedUpStream = EventState[IO].initial[Int, Int](1)(_ + _).flatMap { es =>
  Stream(1, 1, 1).through(es.hookup).compile.toList
}
```
```scala mdoc
hookedUpStream.unsafeRunSync()
```

When using `hookup`, if you only have a single event stream going into your `EventState` then the resulting stream is guaranteed to have all possible state changes.

### SignallingEventState
FS2 has some useful concurrency primitives in the form of `SignallingRef`s and `Topic`s, among others.
`SignallingRef` is useful when you want to use a variable as a signal where you continuously want the latest state change.
`SignallingEventState` is similar in that it has properties just like a `SignallingRef` but it also allows you to continuously stream state changes from it.
In particular, the following two methods are available:
* `continuous` - Get a stream that continuously emits the latest state at the time
* `discrete` - Get a stream of the latest state values, but only when the new state value is distinct from the previous value

You should be aware that **you are not guaranteed every single state change using these methods**.
It is equivallent to repeatedly calling `get` and maybe doing some stateful filtering after.
If you actually want every single resulting state, you can use a single input stream with the `hookup` pipe.
Or, you can use `EventStateTopic` and subscribe.

### EventStateTopic
`EventStateTopic` is a variant of `EventState` that allows you to subscribe to all new state changes, just like an FS2 `Topic`.
It has a new method `subscribe` that returns a stream of all state changes from the moment of subscription, beginning with its current value.
You can also `hookupAndSubscribe` which will concurrently apply all events from the current stream, as well as subscribe and return all events, including ones not from the input stream.
This can be very useful if you have multiple spots where you want to "listen for" new state changes and get all of them, instead of just the latest one.
The downside of doing this approach is it is less efficient, as every single state change must be processed instead of just the latest one available, but sometimes that is the exact semantic that you want.

If you are debugging, look into `ReplayableEventState` from the testing package, which is based on `EventStateTopic`.