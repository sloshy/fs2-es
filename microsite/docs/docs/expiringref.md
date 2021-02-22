---
layout: docs
title: ExpiringRef
permalink: /docs/expiringref/
---
# ExpiringRef
An `ExpiringRef` is a concurrently available value that expires after a certain period of time or number of uses.
When using event-sourcing in particular, it can be helpful to cache event state in memory so that your application is not continuously reading from the event log every time it needs the latest state for something.
This abstraction uses an internal timer that resets after each use so that lifetime management of your state is automated.

Here is a simple example:
```scala mdoc:silent
import cats.effect.IO
import dev.rpeters.fs2.es.data.ExpiringRef
import scala.concurrent.duration._

val timedRef = for {
  res <- ExpiringRef[IO].timed(1, 2.seconds)
  firstResult <- res.use(i => IO.pure(i + 1))
  _ <- res.expired
  secondResult <- res.use(i => IO.pure(i + 2))
} yield (firstResult, secondResult)
```
```scala mdoc
import cats.effect.unsafe.implicits.global

timedRef.unsafeRunSync()
```

With the `.uses` constructor, you can specify a maximum number of uses instead of using a timer.
That said, you may find the timed variant to be more practical for event sourcing.
