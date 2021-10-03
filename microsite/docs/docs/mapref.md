---
layout: docs
title: MapRef
permalink: /docs/mapref/
---
# MapRef
`MapRef` is a small wrapper around an immutable `Map` inside of a `cats.effect.concurrent.Ref`.
You can use it as a map of concurrent values that you can access across your application.

```scala mdoc:silent
import cats.effect.IO
import dev.rpeters.fs2.es.data.MapRef

//Create a concurrent MapRef
val example = MapRef[IO].empty[String, String].flatMap { map =>
  for {
    _ <- map.add("key" -> "value") //Upsert a value to the map
    res1 <- map.get("key")
    _ <- map.del("key")
    res2 <- map.get("key")
  } yield (res1, res2)
}
```
```scala mdoc
import cats.effect.unsafe.implicits.global

example.unsafeRunSync()
```

It has a couple handy extra operators besides just add/get/del operations that you might find useful.
`MapRef#modify` allows you to atomically modify the contents of an entry by-key and return a result value:

```scala mdoc:silent
val exampleModify = MapRef[IO].of(Map("key" -> "value")).flatMap { map =>
  for {
    resFromModify <- map.modify("key")(v => s"$v but modified" -> "result")
    resFromGet <- map.get("key")
  } yield (resFromModify, resFromGet)
}
```
```scala mdoc
exampleModify.unsafeRunSync()
```

As well as `MapRef#upsertOpt` that conditionally either modifies or upserts a value for a given key:

```scala mdoc:silent
val exampleUpsertOpt = MapRef[IO].of(Map("key" -> "value")).flatMap { map =>
  //A helper function for either modifying or inserting a new value
  def upsertFunc(optV: Option[String]): (String, String) = optV match {
      case Some(_) => "value exists" -> "value exists result"
      case None => "new value" -> "new value result"
  }
  for {
    upsertExisting <- map.upsertOpt("key")(upsertFunc)
    upsertNew <- map.upsertOpt("newKey")(upsertFunc)
    resExisting <- map.get("key")
    resNew <- map.get("newKey")
  } yield (upsertExisting, upsertNew, resExisting, resNew)
}
```
```scala mdoc
exampleUpsertOpt.unsafeRunSync()
```
