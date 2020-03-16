//Build
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.1.4")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.3.2")
addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.11")
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "1.3.4")

//Deploy
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.1")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.8.1")
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.13")
