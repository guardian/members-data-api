import scala.sys.process._

val appVersion = "1.0-SNAPSHOT"
name := "members-data-api"

Global / scalaVersion := "2.13.10"

def commitId(): String =
  try { "git rev-parse HEAD".!!.trim }
  catch { case _: Exception => "unknown" }

def buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](
    name,
    "buildNumber" -> Option(System.getenv("BUILD_NUMBER")).getOrElse("DEV"),
    "buildTime" -> System.currentTimeMillis,
    "gitCommitId" -> Option(System.getenv("BUILD_VCS_NUMBER")).getOrElse(commitId()),
  ),
  buildInfoPackage := "app",
  buildInfoOptions += BuildInfoOption.ToMap,
)

val commonSettings = Seq(
  organization := "com.gu",
  version := appVersion,
  resolvers ++= Seq(
    "Guardian Github Releases" at "https://guardian.github.io/maven/repo-releases",
    "Guardian Github Snapshots" at "https://guardian.github.io/maven/repo-snapshots",
    Resolver.sonatypeRepo("releases"),
  ),
  Compile / doc / sources := Seq.empty,
  Compile / packageDoc / publishArtifact := false,
  Global / parallelExecution := false,
  updateOptions := updateOptions.value.withCachedResolution(true),
  Test / javaOptions += "-Dconfig.resource=TEST.public.conf",
  Test / fork := true,
) ++ buildInfoSettings

import com.typesafe.sbt.packager.archetypes.systemloader.ServerLoader.Systemd
val buildDebSettings = Seq(
  Debian / serverLoading := Some(Systemd),
  debianPackageDependencies := Seq("openjdk-11-jre-headless"),
  maintainer := "Membership Dev <membership.dev@theguardian.com>",
  packageSummary := "Members Data API",
  packageDescription := """Members Data API""",
  riffRaffPackageType := (Debian / packageBin).value,
  riffRaffArtifactResources += (file("cloudformation/membership-attribute-service.yaml") -> "cloudformation/membership-attribute-service.yaml"),
  Universal / javaOptions ++= Seq(
    "-Dpidfile.path=/dev/null",
    "-J-XX:MaxRAMFraction=2",
    "-J-XX:InitialRAMFraction=2",
    "-J-XX:MaxMetaspaceSize=500m",
    "-J-XX:+PrintGCDetails",
    "-J-XX:+PrintGCDateStamps",
    s"-J-Xloggc:/var/log/${packageName.value}/gc.log",
  ),
)

val api = Project("membership-attribute-service", file("membership-attribute-service"))
  .enablePlugins(SystemdPlugin, PlayScala, BuildInfoPlugin, RiffRaffArtifact, JDebPackaging)
  .settings(commonSettings)
  .settings(buildDebSettings)
  .settings(
    libraryDependencies ++= Dependencies.apiDependencies,
    dependencyOverrides ++= Dependencies.dependencyOverrides,
    excludeDependencies ++= Dependencies.excludeDependencies,
  )
  .settings(routesGenerator := InjectedRoutesGenerator)
  .settings(
    addCommandAlias("devrun", "run 9400"),
    addCommandAlias("batch-load", "runMain BatchLoader"),
    addCommandAlias("play-artifact", "riffRaffNotifyTeamcity"),
  )

val root = project.in(file(".")).aggregate(api)
