import Dependencies._
import scala.sys.process._

val appVersion = "1.0-SNAPSHOT"
name := "members-data-api"

def commitId(): String =
  try { "git rev-parse HEAD".!!.trim } catch { case _: Exception => "unknown" }

def buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](
    name,
    BuildInfoKey.constant("buildNumber", Option(System.getenv("BUILD_NUMBER")) getOrElse "DEV"),
    BuildInfoKey.constant("buildTime", System.currentTimeMillis),
    BuildInfoKey.constant("gitCommitId",
                          Option(System.getenv("BUILD_VCS_NUMBER")) getOrElse (commitId()))
  ),
  buildInfoPackage := "app",
  buildInfoOptions += BuildInfoOption.ToMap
)

val commonSettings = Seq(
  organization := "com.gu",
  version := appVersion,
  scalaVersion := "2.13.6",
  resolvers ++= Seq(
    "Guardian Github Releases" at "https://guardian.github.io/maven/repo-releases",
    "Guardian Github Snapshots" at "https://guardian.github.io/maven/repo-snapshots",
    Resolver.sonatypeRepo("releases")
  ),
  Compile / doc / sources := Seq.empty,
  Compile / packageDoc / publishArtifact := false,
  Global / parallelExecution := false,
  updateOptions := updateOptions.value.withCachedResolution(true),
  Test / javaOptions += "-Dconfig.resource=TEST.public.conf"
) ++ buildInfoSettings

lazy val dynamoDBLocalSettings = Seq(
  dynamoDBLocalDownloadDir := file("dynamodb-local"),
  startDynamoDBLocal := (startDynamoDBLocal.dependsOn(Test / compile)).value,
  Test / test := (Test / test).dependsOn(startDynamoDBLocal).value,
  Test / testOnly := ((Test / testOnly).dependsOn(startDynamoDBLocal)).evaluated,
  Test / testOptions += (dynamoDBLocalTestCleanup).value
)

import com.typesafe.sbt.packager.archetypes.systemloader.ServerLoader.Systemd
val buildDebSettings = Seq(
  Debian / serverLoading := Some(Systemd),
  debianPackageDependencies := Seq("openjdk-8-jre-headless"),
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
    s"-J-Xloggc:/var/log/${packageName.value}/gc.log"
  )
)

def lib(name: String) =
  Project(name, file(name))
    .enablePlugins(SystemdPlugin, PlayScala, BuildInfoPlugin, RiffRaffArtifact, JDebPackaging)
    .settings(commonSettings)

def app(name: String) =
  lib(name)
    .settings(dynamoDBLocalSettings)
    .settings(buildDebSettings)

val api = app("membership-attribute-service")
  .settings(
    libraryDependencies ++= apiDependencies,
    dependencyOverrides ++= depOverrides,
  )
  .settings(routesGenerator := InjectedRoutesGenerator)
  .settings(
    scalacOptions += "-Ypartial-unification",
    addCommandAlias("devrun", "run 9400"),
    addCommandAlias("batch-load", "runMain BatchLoader"),
    addCommandAlias("play-artifact", "riffRaffNotifyTeamcity")
  )

val root = project.in(file(".")).aggregate(api)
