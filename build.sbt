import microsites._

//Deps
val agitationV = "0.3.0-M1"
val catsEffectV = "3.2.9"
val fs2V = "3.1.3"
val munitV = "0.7.29"
val munitCatsEffectV = "1.0.5"
val scalacheckEffectV = "1.0.2"
val kindProjectorV = "0.13.2"
val skunkV = "0.2.2"

val scala3 = "3.1.2"
val scala213 = "2.13.6"
val scala212 = "2.12.15"

val commonSettings = Seq(
  javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint"),
  scalacOptions := Seq("-target:jvm-1.8")
)

lazy val root = (project in file("."))
  .aggregate(core.js, core.jvm, microsite, testing.js, testing.jvm, files)
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
      "org.typelevel" %%% "cats-effect-kernel" % catsEffectV,
      "org.typelevel" %%% "cats-effect-testkit" % catsEffectV % Test,
      "org.scalameta" %%% "munit-scalacheck" % munitV % Test,
      "org.typelevel" %%% "munit-cats-effect-3" % munitCatsEffectV % "test",
      "org.typelevel" %%% "scalacheck-effect-munit" % scalacheckEffectV % "test"
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    crossScalaVersions := Seq(scala212, scala213, scala3),
    libraryDependencies ++= {
      if (scalaVersion.value.startsWith("2")) {
        Seq(
          compilerPlugin("org.typelevel" % "kind-projector" % kindProjectorV cross CrossVersion.full)
        )
      } else {
        Seq.empty
      }
    }
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )

lazy val testing = (crossProject(JSPlatform, JVMPlatform).crossType(CrossType.Pure) in file("testing"))
  .settings(
    commonSettings,
    name := "fs2-es-testing",
    testFrameworks += new TestFramework("munit.Framework"),
    crossScalaVersions := Seq(scala212, scala213)
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )
  .dependsOn(core % "compile->compile;test->test")

lazy val intCore = (project in file("integration/core"))
  .settings(
    commonSettings,
    name := "fs2-es-integration-core",
    testFrameworks += new TestFramework("munit.Framework"),
    crossScalaVersions := Seq(scala212, scala213)
  )
  .dependsOn(core.jvm % "compile->compile;test->test")

lazy val files = (project in file("integration/files"))
  .settings(
    commonSettings,
    name := "fs2-es-files",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-io" % fs2V
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    crossScalaVersions := Seq(scala212, scala213)
  )
  .dependsOn(intCore % "compile->compile;test->test")

lazy val kafka = (project in file("integration/kafka"))
  .settings(
    commonSettings,
    name := "fs2-es-kafka",
    libraryDependencies ++= Seq(
      "com.github.fd4s" %% "fs2-kafka" % "3.0.0-M2"
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    crossScalaVersions := Seq(scala212, scala213)
  )
  .dependsOn(intCore % "compile->compile;test->test")

lazy val postgresSkunk = (project in file("integration/postgres-skunk"))
  .settings(
    commonSettings,
    name := "fs2-es-pg-skunk",
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "skunk-core" % skunkV
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    crossScalaVersions := Seq(scala212, scala213)
  )
  .dependsOn(intCore % "compile->compile;test->test")

lazy val microsite = (project in file("microsite"))
  .settings(
    scalacOptions ~= filterConsoleScalacOptions,
    publish / skip := true,
    micrositeName := "FS2-ES",
    micrositeDescription := "A small library for event-driven state and event-sourcing for FS2",
    micrositeBaseUrl := "fs2-es",
    micrositeDocumentationUrl := "docs",
    micrositeAuthor := "Ryan Peters",
    micrositeHomepage := "https://sloshy.github.io/fs2-es/",
    micrositeOrganizationHomepage := "https://blog.rpeters.dev",
    micrositeTwitterCreator := "@LiquidSloshalot",
    micrositeExtraMdFiles := Map(
      file("README.md") -> ExtraMdFileConfig(
        "index.md",
        "home",
        Map("permalink" -> "/", "title" -> "Home")
      )
    ),
    micrositeGithubOwner := "sloshy",
    micrositeGithubRepo := "fs2-es",
    micrositeGitterChannelUrl := "fs2-es/community",
    micrositePalette := Map(
      "brand-primary" -> "#871101",
      "brand-secondary" -> "#da2f00",
      "white-color" -> "#ffffff"
    ),
    micrositeHomeButtonTarget := "docs",
    micrositePushSiteWith := GitHub4s,
    micrositeGithubToken := sys.env.get("GITHUB_TOKEN")
  )
  .dependsOn(core.jvm, testing.jvm)
  .enablePlugins(MicrositesPlugin, MdocPlugin)

ThisBuild / scalaVersion := scala213

ThisBuild / developers := List(
  Developer(
    "sloshy",
    "Ryan Peters",
    "me@rpeters.dev",
    url("https://blog.rpeters.dev/")
  )
)
ThisBuild / homepage := Some(url("https://github.com/sloshy/fs2-es/"))
ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / organization := "dev.rpeters"

ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches := Seq(RefPredicate.StartsWith(Ref.Tag("v")))
ThisBuild / githubWorkflowPublish := Seq(
  WorkflowStep.Use(UseRef.Public("ruby", "setup-ruby", "v1"), Map("ruby-version" -> "2.7")),
  WorkflowStep.Run(List("gem install jekyll -v 4")),
  WorkflowStep.Sbt(
    List("ci-release"),
    env = Map(
      "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
      "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
      "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
    )
  ),
  WorkflowStep.Sbt(
    List("microsite/makeMicrosite"),
    cond = Some(s"matrix.scala == $scala212"),
    env = Map("GITHUB_TOKEN" -> "${{ secrets.GITHUB_TOKEN }}")
  )
)
