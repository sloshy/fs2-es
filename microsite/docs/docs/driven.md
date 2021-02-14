---
layout: docs
title: DeferredMap
permalink: /docs/typeclasses/driven/
---
# Driven
Often we say that some applications are "event-driven".
This means that when an event occurs, our application responds by performing some action.
In event-driven code, our states are also driven by events.
This can be expressed as a function `(E, A) => A` where `E` is our event type and `A` is our state.
The `Driven` typeclass indicates that there exists some function like the above that allows us to transition between states when applying events.

```scala mdoc:compile-only
trait Driven[E, A] extends DrivenNonEmpty[E, A] {
  def handleEvent(optA: Option[A])(e: E): Option[A]
}
```

It also has a slightly weaker cousin, `DrivenNonEmpty`:

```scala mdoc:compile-only
trait DrivenNonEmpty[E, A] {
  def handleEvent(a: A)(e: E): Option[A]
  def handleEventOrDefault(a: A)(e: E): A = handleEvent(a)(e).getOrElse(a)
}
```

`DrivenNonEmpty` is for cases where you know you have an initial state value.
For example, say we are folding on a `List[Int]` to find the sum of all the ints in the list.
You could implement that as `DrivenNonEmpty` like so:

```scala mdoc:silent
import dev.rpeters.fs2.es.DrivenNonEmpty

//By hand:
val drivenNonEmptyByHand = new DrivenNonEmpty[Int, Int] {
  def handleEvent(a: Int)(e: Int): Option[Int] = Some(a + e)
}

//Using `cats.Monoid[Int]`
val drivenNonEmptyMonoid = DrivenNonEmpty.monoid[Int]

//Using an arbitrary function
val drivenNonEmptyArb = DrivenNonEmpty.instance[Int, Int] {
  case (2, 2)                   => Some(5) //Obviously 2 + 2 should equal 5
  case (e, a) if (e + a == 666) => None //Forbid evil numbers!
  case (e, a)                   => Some(e + a)
}
```

The `handleEvent` function applies an event to your state, but you may have noticed that it returns an `Option[A]` result value.
This allows us to say that applying an event may not always result in a final value.
For example, in cases where state is "deleted" by an event.
If you know for certain that your state can never be conceptually "deleted", opt to use `handleEventOrDefault` instead which ensures that unhandled cases do not delete state.

`Driven` is more comprehensive, but not applicable to all cases.
`Driven#handleEvent` takes in an optional state, which in this case means that we might not have an initial state to apply events to.
This is because, often with modelling domains, we want to say that certain states are not yet "initialized" until certain events come along.

Lets do a small example showing how to use `Driven` with some event-driven state.
Say we have a `User` model where we want the user's age to be an optional property.
If an event comes along saying the user's name was set, our state value should update.
```scala mdoc:silent
sealed trait UserEvent
final case class UserCreated(name: String) extends UserEvent
final case class UserAgeSet(age: Int) extends UserEvent
case object UserDeleted extends UserEvent

final case class User(name: String, age: Option[Int])

val drivenUser = Driven.instance[UserEvent, User] {
  case (UserCreated(name), None)        => Some(User(name, None))
  case (UserAgeSet(_, age), Some(user)) => Some(user.copy(age = age))
  case (UserDeleted(_), _)              => None
}
```
```scala mdoc
//Creating a user
drivenUser.handleEvent(None)(UserCreated("Josh"))

//Setting age
drivenUser.handleEvent(Some(User("Josh", None)))(UserAgeSet("", 15))

//Deleting the user
drivenUser.handleEvent(Some(User("Josh", 15)))(UserDeleted(""))
```

## Syntax
There is also special syntax available if your driven instance is in implicit scope.
Import `dev.rpeters.fs2.es.syntax._` to get started.

* `handleEvent` for existing states (for `Driven` and `DrivenNonEmpty`)
* `handleEventOrDefault` for existing states (for `Driven` and `DrivenNonEmpty`)
* `handleEvent` on `None` (for `Driven` only)

```scala mdoc:silent
import dev.rpeters.fs2.es.syntax._

implicit val implicitDrivenUser: Driven[UserEvent, User] = drivenUser
```
```scala mdoc
//-----
//Syntax for Driven and DrivenNonEmpty
//-----

User("Josh", 15).handleEvent(UserAgeSet(18))

User("Josh", 15).handleEventOrDefault(UserAgeSet(18))

//-----
//Syntax for Driven only
//-----

Some(User("Josh", 15)).handleEvent(UserAgeSet(18))

None.handleEvent(UserCreated("Jimmy"))
```