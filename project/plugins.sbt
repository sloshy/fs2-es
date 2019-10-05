//Build
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "1.3.5")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.0.6")
addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.8")
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "1.3.3")

//Deploy
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.0")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.8")
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.11")
