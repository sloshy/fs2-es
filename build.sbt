import microsites._

//Deps
val agitationV = "0.2.0"
val catsEffectV = "2.3.1"
val fs2V = "2.5.0"
val munitV = "0.7.21"
val munitCatsEffectV = "0.13.0"
val scalacheckEffectV = "0.7.0"

val scala213 = "2.13.4"
val scala212 = "2.13.5"

val commonSettings = Seq(
  javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint"),
  scalacOptions := Seq("-target:jvm-1.8")
)

lazy val root = (project in file("."))
  .aggregate(core.js, core.jvm, microsite, testing.js, testing.jvm)
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
      "org.scalameta" %%% "munit-scalacheck" % munitV % Test,
      "org.typelevel" %%% "munit-cats-effect-2" % munitCatsEffectV % "test",
      "org.typelevel" %%% "scalacheck-effect-munit" % scalacheckEffectV % "test"
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    crossScalaVersions := Seq(scala212, scala213),
    addCompilerPlugin("org.typelevel" % "kind-projector" % "0.11.3" cross CrossVersion.full)
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
