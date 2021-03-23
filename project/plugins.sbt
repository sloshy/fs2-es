//Build
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.2.17")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")
addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.16")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.5.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.0.0")

//Deploy
addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.7")
addSbtPlugin("com.codecommit" % "sbt-github-actions" % "0.10.1")
addSbtPlugin("com.47deg" % "sbt-microsites" % "1.3.2")
