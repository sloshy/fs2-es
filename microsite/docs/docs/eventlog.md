---
layout: docs
title: EventLog
permalink: docs/eventlog/
---
# EventLog
When doing event-based programming, you may want to keep a log of events.
This is especially helpful for debugging, but also one of the key principles of event-sourcing.
In event-sourcing, your event log is the "source of truth" for your event state, meaning that any changes to state are applied from linear events in your log, in the order that they occur.
In FS2-ES, we provide an `EventLog` interface for you to use to represent accessing and persisting events to your log.

```scala mdoc:compile-only
trait EventLog[F[_], In, Out] {

  //Add events to our event log
  def add(e: In): F[Unit]

  //Stream out all events
  def stream: Stream[F, Out]
}
```

From these two methods, you also get a lot of other, bonus operators that allow you to build up states from your event log.
For example, `getState` will traverse the event log and give you a singleton `Stream` containing a final state value, and `getKeyedState` will give you multiple states at once based on your parameters.
Each of these also allow you to specify whether or not you have a starting state, with different constraints on each.

By default there is only one implementation of this built-in: an in-memory event log.
As the required implementation is rather simple, as shown above, you can make your own integrations with little effort.

## Basic Example

To illustrate how this works, lets use a score-keeping application as an example.
Lets say we have several users, and we want to assign them a point value.
Their points can increase or decrease by any amount.
We can model this as events, and store them in our event log fairly easily:
```scala mdoc:silent
import cats.effect.IO
import dev.rpeters.fs2.es.EventLog

//Our final state we want to calculate.
final case class User(name: String, points: Int)

//Our event algebra.
sealed trait UserEvent {
  //Every event needs a "key". Ours will be the user's name.
  val name: String
  def setName(s: String)
}
final case class UserCreated(name: String) extends UserEvent
final case class UserPointsChanged(name: String, diff: Int) extends UserEvent

val example = EventLog.inMemory[IO, UserEvent].flatMap { log =>
  //Lets create a couple users and assign them points
  val addEvents = Stream[F, UserEvent](
      UserCreated("jack"),
      UserCreated("jill"),
      UserPointsChanged("jack", 5), //Jack gains 5 points
      UserPointsChanged("jill", -20) //Jill loses 20 points
    )
    .evalTap(log.add)
    .compile
    .drain
  
  //Get all of the current states from the event log
  log.stream.fold(Map.empty[String, User]) { case (userMap, event) =>
    event match {
      case UserCreated(name) => 
        //Only add the user if they do not already exist
        userMap.get(name).map(userMap).getOrElse { 
          userMap + User(name, 0)
        }
      case UserPointsChanged(name, points) => 
        userMap.get(name).map { user =>
          //If the user exists, update their points
          val newUser = user.copy(points = user.points + points)
          userMap + (name -> newUser)
        }.getOrElse(userMap - name)
    }
  }
}
```
```scala mdoc
example.unsafeRunSync()
```

## Using Typeclasses
In the above example, we are doing everything, from extracting keys, initializing states, and applying all events, manually and at call-site.
You have the option to do it this way, if you find it simpler, but Scala allows us to program with typeclasses, and we can abstract the above code using them to make the call-site implementation a bit simpler.

FS2-ES includes several typeclasses, and in order to extract state we will need one of the following typeclasses:
* `Driven` - Get a single state value from the entire event log via `getState`
* `DrivenNonEmpty` - Get a single state value from the entire event log using an initial value via `getState(initial)`
* `KeyedState` - Get one or multiple keyed state values via `getOneState(key)` and `getKeyedState` respectfully.
* `KeyedStateNonEmpty` - Get one or multiple keyed state values using initial values via `getOneState(initial, key)` and `getKeyedState(stateMap)` respectfully.

Each of these methods is designed specifically for different kinds of stateful, event-driven programming.
If your event log only represents a single state, you might be best off with `Driven` or `DrivenNonEmpty` and the methods they enable.
Otherwise, if you have multiple states you want to load from your event log, use `KeyedState` or `KeyedStateNonEmpty`.

Our example has events and state with a "key" (the user's name), so we can use `KeyedState`, which also gives us access to all of the above methods due to it being at the top of our typeclass hierarchy.


```scala mdoc:silent
import dev.rpeters.fs2.es.{Driven, Keyed, KeyedState}

//Defines how to extract our state "key" from each event.
implicit val eventKeyed: Keyed[String, UserEvent] = Keyed.instance(_.key)

/** Defines how to apply a `UserEvent` to an `Option[User]`.
  * This is very similar to our previous attempt at doing it by-hand.
  *
  * When defining this, it is best to ignore the "key" field for
  * every event (if applicable) and assume you've already filtered by-key.
  */
implicit val userDriven: Driven[UserEvent, User] = 
  Driven.instance { 
    case (UserCreated(name), None) => Some(User(name, 0))
    case (UserPointsChanged(_, points), Some(user)) => 
      //Add up the points
     Some(user.copy(points = user.points + points))
  }

//We can derive a new KeyedState instance from our Keyed/Driven instances in-scope
implicit val keyedState = KeyedState.instance[String, UserEvent, User]

val simplerExample = EventLog.inMemory[IO, Int].flatMap { log =>
  //Add our events to our log to start, same as before:
  val addEvents = Stream[F, UserEvent](
      UserCreated("jack"),
      UserCreated("jill"),
      UserPointsChanged("jack", 5), //Jack gains 5 points
      UserPointsChanged("jill", -20) //Jill loses 20 points
    )
    .evalTap(log.add)
    .compile
    .drain
  
  //We can get our states by-key now, and even get a map of all known states
  val getJackState =
    log.getOneState[String, User]("jack").unNone.compile.lastOrError

  val getJillState =
    log.getOneState[String, User]("jill").unNone.compile.lastOrError

  val getAllStates =
    log.getKeyedState[String, User].compile.lastOrError

  for {
    _ <- addEvents
    jackState <- getJackState
    jillState <- getJillState
    allStates <- getAllStates
  } yield (jackState, jillState, allStates)
}
```
```scala mdoc
simplerExample.unsafeRunSync()
```