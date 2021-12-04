//Build
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.2.23")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.5")
addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.20")
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.7.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.1.0")

//Deploy
addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.7")
addSbtPlugin("com.codecommit" % "sbt-github-actions" % "0.13.0")
addSbtPlugin("com.47deg" % "sbt-microsites" % "1.3.4")
