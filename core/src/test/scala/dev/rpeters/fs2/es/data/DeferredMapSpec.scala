package dev.rpeters.fs2.es.data

import cats.implicits._
import cats.effect.IO
import cats.effect.concurrent.Deferred
import dev.rpeters.fs2.es.BaseTestSpec

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits._

class DeferredMapSpec extends BaseTestSpec {

  private def newMap = DeferredMap[IO].empty[String, String]
  // private def newMapTryable = DeferredMap[IO].tryableEmpty[String, String]
  val k = "k"
  val v = "v"

  test("add should add a value deferred later") {
    val program = for {
      d <- Deferred[IO, String]
      map <- newMap
      _ <- map.add(k)(d)
      _ <- d.complete(v)
      result <- map.get(k)
    } yield result

    program.unsafeToFuture().map(r => assertEquals(r, v))
  }
  test("add should complete an existing deferred") {
    val program = for {
      map <- newMap
      d1 <- Deferred[IO, String]
      d2 <- Deferred[IO, String]
      _ <- map.add(k)(d1)
      _ <- d2.complete(v)
      _ <- map.add(k)(d2)
      result <- map.get(k)
    } yield result

    program.unsafeToFuture().map(r => assertEquals(r, v))
  }

  test("addF should add a value from an effect") {
    val program = for {
      map <- newMap
      _ <- map.addF(k)(v.pure[IO])
      result <- map.get(k)
    } yield result

    program.unsafeToFuture().map(r => assertEquals(r, v))
  }
  test("addF should complete an existing deferred") {
    val program = for {
      map <- newMap
      d <- Deferred[IO, String]
      _ <- map.add(k)(d)
      _ <- map.addF(k)(v.pure[IO])
      result <- map.get(k)
    } yield result

    program.unsafeToFuture().map(r => assertEquals(r, v))
  }

  test("addPure should add a pure value immediately") {
    val program = for {
      map <- newMap
      _ <- map.addPure(k)(v)
      result <- map.get(k)
    } yield result

    program.unsafeToFuture().map(r => assertEquals(r, v))
  }
  test("addPure should complete an existing deferred") {
    val program = for {
      map <- newMap
      d <- Deferred[IO, String]
      _ <- map.add(k)(d)
      _ <- map.addPure(k)(v)
      result <- map.get(k)
    } yield result

    program.unsafeToFuture().map(r => assertEquals(r, v))
  }

  test("del should remove a key from the map") {
    val program = for {
      map <- newMap
      firstDel <- map.del(k)
      _ <- map.addPure(k)(v)
      first <- map.getOpt(k)
      secondDel <- map.del(k)
      second <- map.getOpt(k)
    } yield (firstDel, first, secondDel, second)

    val expected = (false, Some(v), true, None)

    program.unsafeToFuture().map(r => assertEquals(r, expected))
  }

  test("get should asynchronously get a value for a key") {
    val program = for {
      map <- newMap
      resultFiber <- map.get(k).start
      _ <- IO.sleep(5.seconds)
      _ <- map.addPure(k)(v)
      result <- resultFiber.join
    } yield result

    val running = program.unsafeToFuture()

    tc.tick(5.seconds) //Value is completed after 5 seconds

    running.map(r => assertEquals(r, v))
  }

  test("getOpt should immediately return if key is not awaited") {
    val program = for {
      map <- newMap
      result <- map.getOpt(k)
    } yield result

    program.unsafeToFuture().map(r => assertEquals(r, None))
  }
  test("getOpt should await a value that is not completed yet") {
    val program = for {
      map <- newMap
      d <- Deferred[IO, String]
      _ <- map.add(k)(d)
      resultFiber <- map.getOpt(k).start
      _ <- d.complete(v)
      result <- resultFiber.join
    } yield result

    val running = program.unsafeToFuture()

    tc.tick(1.second)

    running.map(r => assertEquals(r, Some(v)))
  }

  test("getOrAdd should await a value currently being awaited") {
    val program = for {
      map <- newMap
      d1 <- Deferred[IO, String]
      d2 <- Deferred[IO, String]
      _ <- map.add(k)(d1)
      resultFiber <- map.getOrAdd(k)(d2).start
      _ <- d1.complete(v)
      result <- resultFiber.join
    } yield result

    val running = program.unsafeToFuture()

    tc.tick(1.second)

    running.map(r => assertEquals(r, v))
  }
  test("getOrAdd should add a Deferred and await it when the key does not exist") {
    val program = for {
      map <- newMap
      d <- Deferred[IO, String]
      resultFiber <- map.getOrAdd(k)(d).start
      _ <- d.complete(v)
      result <- resultFiber.join
    } yield result

    val running = program.unsafeToFuture()

    tc.tick(1.second)

    running.map(r => assertEquals(r, v))
  }

  test("getOrAddF should await a value currently being awaited") {
    val notV = v + " -- failure"
    val program = for {
      map <- newMap
      d <- Deferred[IO, String]
      _ <- map.add(k)(d)
      resultFiber <- map.getOrAddF(k)(notV.pure[IO]).start
      _ <- d.complete(v)
      result <- resultFiber.join
    } yield result

    val running = program.unsafeToFuture()

    tc.tick(1.second)

    running.map(r => assertEquals(r, v))
  }
  test("getOrAddF should add a Deferred and await it when the key does not exist") {
    val program = for {
      map <- newMap
      result <- map.getOrAddF(k)(v.pure[IO])
    } yield result

    program.unsafeToFuture().map(r => assertEquals(r, v))
  }

  test("getOrAddPure should await a value currently being awaited") {
    val notV = v + " -- failure"
    val program = for {
      map <- newMap
      d <- Deferred[IO, String]
      _ <- map.add(k)(d)
      resultFiber <- map.getOrAddPure(k)(notV).start //Force this to fail if it is unlawful
      _ <- d.complete(v)
      result <- resultFiber.join
    } yield result

    val running = program.unsafeToFuture()

    tc.tick(1.second)

    running.map(r => assertEquals(r, v))
  }
  test("getOrAddPure should add a Deferred and await it when the key does not exist") {
    val program = for {
      map <- newMap
      result <- map.getOrAddPure(k)(v)
    } yield result

    program.unsafeToFuture().map(r => assertEquals(r, v))
  }
}
