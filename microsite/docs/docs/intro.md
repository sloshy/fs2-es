---
layout: docs
title: Introduction
permalink: docs/
---
# Introduction
Modeling state with events can be simple to understand, but difficult to master.
You may have heard of "event sourcing", or perhaps the ["Flux"](https://facebook.github.io/flux/) and ["Redux"](https://redux.js.org/) models of application architecture in JavaScript.
In each of these, there is a common thread of having event-driven systems where the only way to modify state is by applying a linear sequence of events to your state.

One easy way to model this is using `fold` in functional languages.
For example, here is a basic way to get some event-driven state using "fold", written using the FS2 streaming library:

```scala mdoc:silent
import cats.implicits._
import fs2.{Pipe, Pure, Stream}

//A function that takes our stream and computes a final result state
def buildState: Pipe[Pure, Int, Int] = s => s.fold(0)(_ + _)

val incomingEvents = Stream(1, 2, 3)
```
```scala mdoc
//Now we run our program and observe the resulting state
incomingEvents.through(buildState).compile.last
```

There are several advantages to building your state from events, especially if they hold the following properties:
* Events are immutable and never change
* Events represent things that have happened, and not intentions to perform a specific action
* The order of events is strictly linear for any "aggregate root" (a single unit of state that does not depend on any parent relationship).

In trying to achieve these properties, certain patterns emerge that this library hopes to properly encode.
I personally take the view that overly-opinionated frameworks around event sourcing are a bad idea as they not only constrain the entire design of your progam but they also make it harder to be more flexible with the definition of event-driven state that you happen to employ.
For example, many frameworks make an opinionated decision about where you store your event log.
This library has nothing to say about persistence, only functionality related to restoring and managing the lifetimes of state from events.
You can very easily build your own event log just by serializing events and putting them in a database table, Apache Kafka or Pulsar, or even to a raw file for example, and in my opinion that is the easiest part of this to "get right" on your own.

This library chooses to focus on some of the more easily composable parts of event sourcing.
To that end, it comes with a few useful utilities you should get to know.

If you are just getting started, start learning with [`EventState`](eventstate.md) and the various [`Type Classes`](typeclasses.md)

