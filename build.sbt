import BuildSettings.*
import Dependencies.*
import PublishSettings.*
import TestEnvironment.*

ThisBuild / scalaVersion := scalaV
//ThisBuild / conflictManager := ConflictManager.strict

lazy val pactOptions: Seq[Tests.Argument] = Seq(
  Some(sys.env.getOrElse("PACT_BROKER_BASE_URL", "http://localhost:9292")).map(s => s"-Dpactbroker.url=$s"),
  sys.env.get("PACT_BROKER_TOKEN").map(s => s"-Dpactbroker.auth.token=$s"),
  sys.env.get("PACT_BROKER_TAG").map(s => s"-Dpactbroker.consumerversionselectors.tags=$s"),
).flatten.map(o => Tests.Argument(jupiterTestFramework, o))

lazy val plugin = moduleProject("plugin", "plugin")
  .enablePlugins(
    GitHubPagesPlugin,
    JavaAppPackaging,
    LauncherJarPlugin
  )
  .settings(
    git.useGitDescribe := true,
    name := "plugin",
    maintainer := "aliustek@gmail.com",
    publishSettings,
    testEnvSettings,
    gitHubPagesOrgName := "austek",
    gitHubPagesRepoName := "pact-avro-plugin",
    gitHubPagesSiteDir := (`pact-avro-plugin` / baseDirectory).value / "build" / "site",
    gitHubPagesAcceptedTextExtensions := Set(".css", ".html", ".js", ".svg", ".txt", ".woff", ".woff2", ".xml"),

    // Remove the resourceGenerators section entirely

    // Add manifest creation to the stage task instead
    Universal / stage := {
      val stageDir = (Universal / stage).value
      val manifestFile = stageDir / "pact-plugin.json"
      val pluginVersion = version.value
      val manifestContent = s"""{
        |  "manifestVersion": 1,
        |  "name": "avro",
        |  "version": "$pluginVersion",
        |  "pluginInterfaceVersion": 1,
        |  "entryPoint": "bin/pact-avro-plugin",
        |  "entryPoints": {
        |    "linux": {
        |      "type": "exec",
        |      "path": "bin/pact-avro-plugin"
        |    },
        |    "osx": {
        |      "type": "exec",
        |      "path": "bin/pact-avro-plugin"
        |    },
        |    "windows": {
        |      "type": "exec",
        |      "path": "bin/pact-avro-plugin.bat"
        |    }
        |  },
        |  "dependencies": []
        |}""".stripMargin

      IO.write(manifestFile, manifestContent)
      stageDir
    },

    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
    ),
    libraryDependencies ++=
      Dependencies.compile(apacheAvro, auPactMatchers, logback, scalaLogging, scalaPBRuntime) ++
        Dependencies.protobuf(scalaPB) ++
        Dependencies.test(scalaTest),
    dependencyOverrides ++= Seq(grpcApi, grpcCore, grpcNetty)
  )

lazy val pluginRef = LocalProject("plugin")

lazy val consumer = moduleProject("consumer", "examples/consumer")
  .settings(
    Test / fork := true,
    Test / envVars := sys.env.get("PACT_PLUGIN_DIR")
      .map(dir => Map("PACT_PLUGIN_DIR" -> dir))
      .getOrElse(Map.empty),
    Test / javaOptions ++= Seq(
          s"-Dpact.plugin.dir=${System.getProperty("user.home")}/.pact/plugins"
        ),
    Compile / avroSource := (Compile / resourceDirectory).value / "avro",
    libraryDependencies ++=
      Dependencies.compile(avroCompiler, logback, pulsar4sCore, pulsar4sAvro, scalaLogging) ++
        Dependencies.test(assertJCore, jUnitInterface, pactConsumerJunit),
    publish / skip := false
  )

lazy val provider = moduleProject("provider", "examples/provider")
  .settings(
    Test / fork := true,
    Test / envVars := sys.env.get("PACT_PLUGIN_DIR")
      .map(dir => Map("PACT_PLUGIN_DIR" -> dir))
      .getOrElse(Map.empty),
    testOptions ++= pactOptions,
    libraryDependencies ++=
      Dependencies.compile(avroCompiler, logback, pulsar4sCore, pulsar4sAvro, scalacheck) ++
        Dependencies.test(assertJCore, jUnitInterface, pactProviderJunit),
    publish / skip := false
  )

lazy val `pact-avro-plugin` = (project in file("."))
  .aggregate(
    pluginRef,
    consumer,
    provider
  )
  .settings(
    basicSettings,
    publish / skip := false
  )

def moduleProject(name: String, path: String): Project = {
  Project(name, file(s"modules/$path"))
    .enablePlugins(GitVersioning, ScalafmtPlugin)
    .settings(
      basicSettings,
      moduleName := name,
      git.useGitDescribe := true,
      // FIX: Ensure version is always semantic version compatible
      git.gitTagToVersionNumber := { tag: String =>
        if(tag matches "v?[0-9]+\\.[0-9]+\\.[0-9]+.*") {
          // Valid semver tag
          Some(tag.stripPrefix("v"))
        } else {
          // Fallback to a default semver for non-tagged commits
          None
        }
      },
      // Add fallback version when git describe doesn't produce valid semver
      version := {
        git.gitDescribedVersion.value match {
          case Some(v) if v.matches("[0-9]+\\.[0-9]+\\.[0-9]+.*") => v
          case Some(v) =>
            // If git describe gives us something like "0.1.0-23-g10eb91a"
            // Extract just the semver part
            v.split("-").headOption.getOrElse("0.1.0-SNAPSHOT")
          case None => "0.1.0-SNAPSHOT"
        }
      }
    )
}
