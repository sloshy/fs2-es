---
layout: docs
title: Introduction
permalink: docs/
---
# Introduction
Modelling state with events can be simple to understand, but difficult to master.
You may have heard of "event sourcing", or perhaps the "Flux" and "Redux" models of application architecture in JavaScript.
In each of these, there lies a common thread of having event-driven systems where the only way to modify state is by taking in a linear sequence of events as they occur.

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
Start with `EventState` in the sidebar and continue from there.

## What to use?
If you are doing a small event-sourced program and maybe only have a few, finite sources of event-sourced state, you can get by with only `EventState` just fine.
If you have a number that you are quite confident should fit in memory, but might be dynamic for other reasons, make a `MapRef[K, EventState]` or use some other pattern/structure to organize your state.
If you need custom lifetime management built on top of that, feel free to write your own structures using `ExpiringRef` as well on top of that, or on the side as-needed.
Lastly, if you need all of that plus a key/value repository interface for your event-sourced state, `EventStateCache` should give you everything you need at once.
It not only handles retrieving your state from your event log as you define it, but it also makes sure that you do not waste precious time or resources re-running the same event log queries by caching state in-memory.

If you do not need "the full package" you should very easily be able to build what you need with each of the smaller parts that make up one `EventStateCache`.
Try it out, see what works for you, and if you were able to build something that fit your use cases better with it, be sure to let me know!

Happy event sourcing!