package dev.rpeters.fs2.es.data

import dev.rpeters.fs2.es.BaseTestSpec
import cats.syntax.all._
import cats.effect.IO
import org.scalacheck.effect.PropF._
import scala.collection.immutable.Queue

class LRUSpec extends BaseTestSpec {
  test("Should behave like an LRU cache") {
    forAllNoShrinkF { baseKey: String =>
      LRU[IO, String].flatMap { lru =>
        val key1 = baseKey + "1"
        val key2 = baseKey + "2"
        for {
          _ <- lru.dump.assertEquals(Queue.empty, "Should start empty")
          _ <- lru.use(key1).assertEquals(1)
          _ <- lru.use(key2).assertEquals(2)
          _ <- lru.dump.assertEquals(Queue(key1, key2))
          _ <- lru.use(key1).assertEquals(2, "Should only enqueue unique elements")
          _ <- lru.dump.assertEquals(Queue(key2, key1), "Use moves an element to the front")
          _ <- lru.del(key1).assertEquals(1)
          _ <- lru.dump.assertEquals(Queue(key2))
          _ <- lru.use(key1).assertEquals(2)
          _ <- lru.dump.assertEquals(Queue(key2, key1))
          _ <- lru.pop.assertEquals(Some(key2), "Pop returns the least recent element")
          _ <- lru.dump.assertEquals(Queue(key1), "Pop removes that element from the queue")
        } yield ()
      }
    }
  }
}
