//Deps
val fs2V = "2.0.1"

lazy val root = (project in file("."))
  .settings(
    name := "fs2-es",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % fs2V,
      "io.chrisdavenport" %% "agitation" % "0.2.0-M1",
      "org.typelevel" %% "cats-effect-laws" % "2.0.0" % Test,
      "org.scalacheck" %% "scalacheck" % "1.14.2" % Test,
      "org.scalatest" %% "scalatest" % "3.0.8" % Test
    )
  )

lazy val docs = (project in file("fs2-es-docs"))
  .settings(
    scalacOptions ~= filterConsoleScalacOptions,
    scalaVersion := "2.13.0" //Fix for a weird bug going on
  )
  .dependsOn(root)
  .enablePlugins(MdocPlugin)

ThisBuild / scalaVersion := "2.13.1"
