# FS2-ES
![Sonatype Nexus (Releases)](https://img.shields.io/nexus/r/dev.rpeters/fs2-es_2.13?label=latest&server=https%3A%2F%2Foss.sonatype.org) [![Gitter](https://badges.gitter.im/fs2-es/community.svg)](https://gitter.im/fs2-es/community?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)

Javadoc (core library):   [![javadoc](https://javadoc.io/badge2/dev.rpeters/fs2-es_2.13/javadoc.svg)](https://javadoc.io/doc/dev.rpeters/fs2-es_2.13/latest/dev/rpeters/fs2/es/index.html)

Javadoc (testing module): [![javadoc](https://javadoc.io/badge2/dev.rpeters/fs2-es-testing_2.13/javadoc.svg)](https://javadoc.io/doc/dev.rpeters/fs2-es-testing_2.13/latest/dev/rpeters/fs2/es/index.html)

This is a small library to encode event-sourcing patterns using FS2, a streaming library in Scala.
The library is polymorphic using Cats Effect, so you can use it with any effect type you want that implements `cats.effect.Concurrent`.

**This library is in active development and the API may change without warning**

To use, add the library to your `build.sbt` like so:
```
libraryDependencies += "dev.rpeters" %% "fs2-es" % "<latest-version>"
libraryDependencies += "dev.rpeters" %% "fs2-es-testing" % "<latest-version>" //Test module
```

Currently Scala 2.12 and 2.13 are both supported. Project is built for Scala JVM and ScalaJS 1.x+.

## Documentation:
* [Introduction](docs/Introduction.md) - Concepts and constructs implemented by this library
* [Testing module](docs/Testing.md) - Additional utilities to enable time travel debugging in reactive applications