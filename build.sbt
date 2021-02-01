import xerial.sbt.Sonatype._
//Deps
val agitationV = "0.2.0"
val catsEffectV = "2.3.1"
val fs2V = "2.5.0"
val munitV = "0.7.21"

val scala213 = "2.13.4"
val scala212 = "2.12.13"

val commonSettings = Seq(
  organization := "dev.rpeters",
  javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint"),
  scalacOptions := Seq("-target:jvm-1.8"),
  sonatypeProjectHosting := Some(GitHubHosting("sloshy", "fs2-es", "me@rpeters.dev"))
)

lazy val root = (project in file("."))
  .aggregate(core.js, core.jvm, testing.js, testing.jvm)
  .settings(
    commonSettings,
    publish / skip := true
  )

lazy val core = (crossProject(JSPlatform, JVMPlatform).crossType(CrossType.Pure) in file("core"))
  .settings(
    commonSettings,
    name := "fs2-es",
    libraryDependencies ++= Seq(
      "co.fs2" %%% "fs2-core" % fs2V,
      "io.chrisdavenport" %%% "agitation" % agitationV,
      "org.typelevel" %%% "cats-effect-laws" % catsEffectV % Test,
      "org.scalameta" %%% "munit-scalacheck" % munitV % Test
    ),
    publishTo := sonatypePublishToBundle.value,
    testFrameworks += new TestFramework("munit.Framework"),
    crossScalaVersions := Seq(scala212, scala213)
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )

lazy val testing = (crossProject(JSPlatform, JVMPlatform).crossType(CrossType.Pure) in file("testing"))
  .settings(
    commonSettings,
    name := "fs2-es-testing",
    publishTo := sonatypePublishToBundle.value,
    testFrameworks += new TestFramework("munit.Framework"),
    crossScalaVersions := Seq(scala212, scala213)
  )
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val docs = (project in file("fs2-es-docs"))
  .settings(
    scalacOptions ~= filterConsoleScalacOptions,
    publish / skip := true
  )
  .dependsOn(testing.jvm)
  .enablePlugins(MdocPlugin)

ThisBuild / scalaVersion := scala213

ThisBuild / publishMavenStyle := true

ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))

import ReleaseTransformations._

ThisBuild / releaseCrossBuild := true
ThisBuild / releaseProcess := Seq[ReleaseStep](
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
