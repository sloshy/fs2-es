//Deps
val fs2V = "2.4.2"

val scala213 = "2.13.2"
val scala212 = "2.12.11"

lazy val root = (project in file("."))
  .aggregate(core.js, core.jvm)
  .settings(
    publish := {},
    publishLocal := {}
  )

lazy val core = (crossProject(JSPlatform, JVMPlatform).crossType(CrossType.Pure) in file("core"))
  .settings(
    name := "fs2-es",
    organization := "dev.rpeters",
    libraryDependencies ++= Seq(
      "co.fs2" %%% "fs2-core" % fs2V,
      "io.chrisdavenport" %%% "agitation" % "0.2.0",
      "org.typelevel" %%% "cats-effect-laws" % "2.1.3" % Test,
      "org.scalameta" %%% "munit-scalacheck" % "0.7.9" % Test
    ),
    publishTo := sonatypePublishToBundle.value,
    testFrameworks += new TestFramework("munit.Framework"),
    crossScalaVersions := Seq(scala212, scala213),
    javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint"),
    scalacOptions := Seq("-target:jvm-1.8")
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )

lazy val docs = (project in file("fs2-es-docs"))
  .settings(
    scalacOptions ~= filterConsoleScalacOptions,
    publish / skip := true
  )
  .dependsOn(core.jvm)
  .enablePlugins(MdocPlugin)

ThisBuild / scalaVersion := scala213

publishMavenStyle := true

licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))

import xerial.sbt.Sonatype._
sonatypeProjectHosting := Some(GitHubHosting("sloshy", "fs2-es", "me@rpeters.dev"))

import ReleaseTransformations._

releaseCrossBuild := true
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommandAndRemaining("+publishSigned"),
  releaseStepCommand("sonatypeBundleRelease"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
