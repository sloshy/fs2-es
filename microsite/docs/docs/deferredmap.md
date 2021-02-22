---
layout: docs
title: DeferredMap
permalink: /docs/deferredmap/
---
# DeferredMap
A `DeferredMap` is a concurrent [`MapRef`](mapref/) that is specifically optimized for awaiting asynchronous values that may not have completed yet.
It is used internally by [`EventStateCache`](eventstatecache/) to keep track of what resources are already being awaited, so you do not duplicate requests.

Here's a brief example of how you can use it, for awaiting a specific concurrent job to finish, by-key:

```scala mdoc:silent
import cats.effect._
import cats.effect.Deferred
import dev.rpeters.fs2.es.data.DeferredMap
import scala.concurrent.duration._

val example = DeferredMap[IO].empty[String, String].flatMap { dmap =>
  //Helper function to complete a job concurrently after three seconds
  def completeJobAfter3s(key: String) = for {
    d <- Deferred[IO, String] //Create our async result that has not finished yet
    _ <- dmap.add(key)(d) //Add it to the map
    _ <- IO.sleep(3.seconds).flatMap(_ => d.complete("success")).start //Complete it asynchronously
  } yield ()
  
  for {
    _ <- completeJobAfter3s("job1")
    _ <- completeJobAfter3s("job2")
    res1 <- dmap.get("job1") //Both of these will await for their job to finish
    res2 <- dmap.get("job2")
  } yield (res1, res2)
}
```
```scala mdoc
import cats.effect.unsafe.implicits.global

example.unsafeRunSync()
```

The API has a lot of nice helper methods for the typical use cases, such as getting a value only if it is present in the map first (`getOpt`), or "upsert" semantics where you either await a result or add your own (`getOrAdd` and `getOrAddF`).
It also has operators for checking whether a value is completed or not via the `try*` operators.
Please see the API docs for more information.

**BE ADVISED:** This is a rather low-level concurrency tool and you will want to thoroughly test your usage of this in order to not leak anything.
Always be sure to delete values that have completed after some period of time, or make sure they expire with [`ExpiringRef`](expiringref/).
