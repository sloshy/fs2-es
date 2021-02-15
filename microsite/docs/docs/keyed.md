---
layout: docs
title: Keyed
permalink: /docs/typeclasses/keyed/
---
# Keyed
When dealing with multiple event-driven states, you need to know which events apply to which state.
To do this, each event needs some kind of "key" that you can extract from it, as a unique identifier for your states.
The `Keyed` type class is a very, very basic type class that expresses this relationship:

```scala mdoc:compile-only
trait Keyed[K, A] {
  //Extract a key K from some A value
  def getKey(a: A): K
}
```

As you can see, this is little more than a function `A => K`, and as such it's almost entirely lawless.
You can create an implementation for your own code fairly easily:

```scala mdoc:silent
import dev.rpeters.fs2.es.Keyed
import dev.rpeters.fs2.es.syntax._

//Assume we have state that looks like this:
case class User(name: String)

//State is managed with events in this hierarchy
sealed trait UserEvent {
  val name: String
}
case class UserCreated(name: String) extends UserEvent

//The user's name is the "key" for our events
implicit val keyed: Keyed[String, UserEvent] = Keyed.instance(_.name)
```
```scala mdoc
UserCreated("Ryan").getKey
```

When used together with [`Driven`](driven.md) or `DrivenNonEmpty`, you can form a [`KeyedState`](keyedstate.md) instance.
This can be useful with certain methods on [`EventLog`](eventlog.md) and [`EventStateCache`](eventstatecache.md) which require it for streaming keyed state.
