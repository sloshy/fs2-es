---
layout: docs
title: EventState
permalink: /docs/eventstate/
---
# EventState
When modeling your applications, you will occasionally want pieces of state that respond to events.
You can build your own state machine if you know what you're doing, but it can help to have a ready-made primitive for that at your fingertips.
To that end, `EventState` is a common abstraction to help you manage best practices for dealing with event-sourced state.
You can create one empty, or with an initial value, and then apply events and streams to them to modify their state.
The only way to modify their state is via events, and there is no other way to do so.
In this way it is more restricted than `cats.effect.concurrent.Ref`, which you might already be using for mutable state, as it enforces this event-driven model.

```scala mdoc:compile-only
trait EventState[F[_], E, A] {

  //Applies an event to our state
  def doNext(e: E): F[A]

  //Gets the current value of state.
  def get: F[A]
}
```

To create one, you will need a `Driven` or `DrivenNonEmpty` instance for your state in scope:

```scala mdoc:silent
import cats.syntax.all._
import dev.rpeters.fs2.es.{DrivenNonEmpty, EventState}

//Uses Monoid[Int] to add Int events to Int state
implicit val intDriven: DrivenNonEmpty[Int, Int] = DrivenNonEmpty.monoid

//Creates an EventState that sums ints together
val getIntEventState: IO[EventState[IO, Int, Int]] = EventState[IO].total[Int, Int](0)

val intEventStateExample = getIntEventState.flatMap { es =>
  //Add 1 to the initial state of 0 three times
  es.doNext(1) >> es.doNext(1) >> es.doNext(1)
}
```
```scala mdoc
intEventStateExample.unsafeRunSync()
```

## Constructors
The above example shows but one constructor available for `EventState`, the `total` constructor.
There are several others that you should get to know with different properties, including:

* `empty` - Has an optional state starting as `None`. Needs a `Driven` instance.
* `initial` - Has an initial state value as `Some`. Needs a `Driven` instance.
* `total` - Has an initial state value and does not use optional state. Needs a `DrivenNonEmpty` instance.
* `manualEmpty`, `manualInitial`, and `manualTotal` - Alternative versions of the above that do not use typeclasses and allow you to supply your own manual functions.

Using the above constructors you can create different `EventState` instances that are useful in different situations.
For example, `empty` is useful in cases where state can be "initialized" by an incoming event, i.e. `UserCreatedEvent` in a system that models users.
`initial` and `total` imply you supply your own starting state, and differ in whether or not you allow state to be "deleted" by being set to `None` by events.
For quick demos, scripts, or ad-hoc usage with different configurations, the `manual` variants of the above can also be useful.

## SignallingEventState and EventStateTopic
Under the hood, `EventState` is built on top of `cats.effect.Ref`, a structure for concurrent mutable data.
The FS2 library provides a couple other concurrent state utilities that we might want to take advantage of, so for those situations, we have `SignallingEventState` and `EventStateTopic`.
As their names imply, they are based on `SignallingRef` and `Topic` respectively, and are all based on the same underlying implementation, with some bonus features.

`SignallingEventState`, just like `SignallingRef` in FS2, allows you to continuously stream the current state value as a signal.
`EventStateTopic`, being based on FS2's `Topic`, will publish all state changes to a `Topic` for multiple subscribers to listen in on.
If you are doing signal processing, where you occasionally check a state value but only care about what state it is *right now* and not any intermediate states, `SignallingEventState` might be for you.
On the other hand, if you want to broadcast your state changes across your application, look into `EventStateTopic`.

If you are debugging, look into [`ReplayableEventState`](/docs/testing/) from the testing package, which is based on `EventStateTopic` and also allows you to seek forwards and backwards through your states, using time-travel debugging.