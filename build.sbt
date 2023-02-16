lazy val zioVersion = "2.0.9"
lazy val gitCommitString = SettingKey[String]("gitCommit")

lazy val commonSettings = Seq(
  organization := "org.geneontology",
  version := "2.3.1",
  licenses := Seq("MIT license" -> url("https://opensource.org/licenses/MIT")),
  homepage := Some(url("https://github.com/balhoff/relation-graph")),
  scalaVersion := "2.13.10",
  scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8"),
  javaOptions += "-Xmx8G"
)

lazy val publishSettings = Seq(
  Test / publishArtifact := false,
  publishMavenStyle := true,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
    else Some("releases" at nexus + "service/local/staging/deploy/maven2")
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
      "org.geneontology" %% "whelk-owlapi" % "1.1.2",
      "org.apache.jena" % "apache-jena-libs" % "4.6.1" exclude("org.slf4j", "slf4j-log4j12"),
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test
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
      "com.outr" %% "scribe-slf4j" % "3.10.4",
      "com.github.alexarchambault" %% "case-app" % "2.0.6",
      "io.circe" %% "circe-yaml" % "0.14.2",
    ),
    gitCommitString := git.gitHeadCommit.value.getOrElse("Not Set"),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, gitCommitString),
    buildInfoPackage := "org.renci.relationgraph"
  )

Global / useGpg := false
