# FS2-ES
![Sonatype Nexus (Releases)](https://img.shields.io/nexus/r/dev.rpeters/fs2-es_2.13?label=latest&server=https%3A%2F%2Foss.sonatype.org) [![Gitter](https://badges.gitter.im/fs2-es/community.svg)](https://gitter.im/fs2-es/community?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)

This is a small library to encode event-sourcing patterns using FS2, a streaming library in Scala.
The library is polymorphic using Cats Effect, so you can use it with any effect type you want that implements `cats.effect.Concurrent`.

To use, add the library to your `build.sbt` like so:
```
libraryDependencies += "dev.rpeters" %% "fs2-es" % "<latest-version>"
libraryDependencies += "dev.rpeters" %% "fs2-es-testing" % "<latest-version>" //Test module
```

Currently Scala 2.12 and 2.13 are both supported. Project is built for Scala JVM and ScalaJS 1.4+.

## Features
This library has three core focuses:

* [State Management](https://sloshy.github.io/fs2-es/docs/eventstate/) - Use "EventState" to model event-driven state that is safe, concurrent, and with no boilerplate.
* [Event Sourcing](https://sloshy.github.io/fs2-es/docs/eventstatecache/) - Manage entities using event sourcing patterns with "EventStateCache", a standard repository interface.
* [Test Utilities](https://sloshy.github.io/fs2-es/docs/testing/) - Utilize time-travel debugging features and other goodies to analyze your state as it changes.

Click any of the links above to go to the relevant parts of the documentation on our microsite.

## API Docs
* [Core](https://javadoc.io/doc/dev.rpeters/fs2-es_2.13/latest/dev/rpeters/fs2/es/index.html)
* [Testing](https://javadoc.io/doc/dev.rpeters/fs2-es-testing_2.13/latest/dev/rpeters/fs2/es/testing/index.html)
