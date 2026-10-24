lazy val zioVersion = "2.1.22"
lazy val scribeVersion = "3.17.0"
lazy val gitCommitString = SettingKey[String]("gitCommit")

lazy val commonSettings = Seq(
  organization := "org.geneontology",
  version := "3.0.0",
  licenses := Seq("MIT license" -> url("https://opensource.org/licenses/MIT")),
  homepage := Some(url("https://github.com/balhoff/relation-graph")),
  scalaVersion := "2.13.17",
  scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8"),
  javaOptions += "-Xmx8G"
)

lazy val publishSettings = Seq(
  Test / publishArtifact := false,
  publishMavenStyle := true,
  // sbt-git's GitVersioning (enabled for cli) otherwise derives isSnapshot from git state for the whole build
  isSnapshot := version.value.endsWith("-SNAPSHOT"),
  publishTo := {
    val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
    if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
    else localStaging.value
  },
  pomExtra :=
    <developers>
      <developer>
        <id>balhoff</id>
        <name>Jim Balhoff</name>
        <email>balhoff@renci.org</email>
      </developer>
    </developers>
)

lazy val parentProject = project
  .in(file("."))
  .settings(commonSettings)
  .settings(name := "relation-graph-project", publish / skip := true)
  .aggregate(core, cli)

lazy val core = project
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "relation-graph",
    description := "relation-graph core",
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "org.geneontology" %% "whelk-owlapi" % "2.0.0",
      "org.apache.jena" % "apache-jena-libs" % "4.10.0" exclude("org.slf4j", "slf4j-log4j12"),
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6",
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "com.outr" %% "scribe-slf4j2" % scribeVersion % Test
    )
  )
  .settings(publishSettings)

lazy val cli = project
  .in(file("cli"))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(GitVersioning)
  .settings(commonSettings)
  .dependsOn(core)
  .settings(
    name := "relation-graph-cli",
    executableScriptName := "relation-graph",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "com.outr" %% "scribe-slf4j2" % scribeVersion,
      "com.github.alexarchambault" %% "case-app" % "2.1.0",
      "io.circe" %% "circe-yaml" % "0.14.2",
    ),
    gitCommitString := git.gitHeadCommit.value.getOrElse("Not Set"),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, gitCommitString),
    buildInfoPackage := "org.renci.relationgraph"
  )

Global / useGpg := false
