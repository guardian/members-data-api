import Dependencies._

val appVersion = "1.0-SNAPSHOT"
name := "members-data-api"

def buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](
    name,
    BuildInfoKey.constant("buildNumber", Option(System.getenv("BUILD_NUMBER")) getOrElse "DEV"),
    BuildInfoKey.constant("buildTime", System.currentTimeMillis),
    BuildInfoKey.constant("gitCommitId", Option(System.getenv("BUILD_VCS_NUMBER")) getOrElse(try {
      "git rev-parse HEAD".!!.trim
    } catch {
      case e: Exception => "unknown"
    }))
  ),
  buildInfoPackage := "app",
  buildInfoOptions += BuildInfoOption.ToMap
)

val commonSettings = Seq(
  organization := "com.gu",
  version := appVersion,
  scalaVersion := "2.11.8",
  resolvers ++= Seq(
    "Guardian Github Releases" at "https://guardian.github.io/maven/repo-releases",
    "Guardian Github Snapshots" at "http://guardian.github.com/maven/repo-snapshots",
    "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases",
    Resolver.bintrayRepo("dwhjames", "maven"),
    Resolver.sonatypeRepo("releases")),
  sources in (Compile,doc) := Seq.empty,
  publishArtifact in (Compile, packageDoc) := false,
  parallelExecution in Global := false,
  updateOptions := updateOptions.value.withCachedResolution(true),
  javaOptions in Test += "-Dconfig.resource=TEST.public.conf"
) ++ buildInfoSettings

lazy val dynamoDBLocalSettings = Seq(
  dynamoDBLocalDownloadDir := file("dynamodb-local"),
  startDynamoDBLocal := (startDynamoDBLocal.dependsOn(compile in Test)).value,
  test in Test := (test in Test).dependsOn(startDynamoDBLocal).value,
  testOnly in Test := ((testOnly in Test).dependsOn(startDynamoDBLocal)).evaluated,
  testOptions in Test += (dynamoDBLocalTestCleanup).value
)

import com.typesafe.sbt.packager.archetypes.ServerLoader.Systemd

val buildDebSettings = Seq(
  serverLoading in Debian := Systemd,
  debianPackageDependencies := Seq("openjdk-8-jre-headless"),
  maintainer := "Membership Dev <membership.dev@theguardian.com>",
  packageSummary := "Members Data API",
  packageDescription := """Members Data API""",

  riffRaffPackageType := (packageBin in Debian).value,

  javaOptions in Universal ++= Seq(
    "-Dpidfile.path=/dev/null",
    "-J-XX:MaxRAMFraction=2",
    "-J-XX:InitialRAMFraction=2",
    "-J-XX:MaxMetaspaceSize=500m",
    "-J-XX:+PrintGCDetails",
    "-J-XX:+PrintGCDateStamps",
    s"-J-Xloggc:/var/log/${packageName.value}/gc.log"
  )
)

def lib(name: String) = Project(name, file(name))
  .enablePlugins(PlayScala, BuildInfoPlugin, RiffRaffArtifact, JDebPackaging).settings(commonSettings)

def app(name: String) = lib(name)
  .settings(dynamoDBLocalSettings)
  .settings(buildDebSettings)

val api = app("membership-attribute-service")
  .settings(libraryDependencies ++= apiDependencies)
  .settings(routesGenerator := InjectedRoutesGenerator)
  .settings(
    addCommandAlias("devrun", "run 9400"),
    addCommandAlias("batch-load", "runMain BatchLoader"),
    addCommandAlias("play-artifact", "riffRaffNotifyTeamcity")
  )

val root = project.in(file(".")).aggregate(api)