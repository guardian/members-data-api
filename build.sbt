import scala.sys.process._
import scala.io.Source
import scala.util.Try

val appVersion = "1.0-SNAPSHOT"
name := "members-data-api"

def commitId(): String =
  try { "git rev-parse HEAD".!!.trim }
  catch { case _: Exception => "unknown" }

def buildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](
    name,
    BuildInfoKey.constant("buildNumber", Option(System.getenv("BUILD_NUMBER")) getOrElse "DEV"),
    BuildInfoKey.constant("buildTime", System.currentTimeMillis),
    BuildInfoKey.constant("gitCommitId", Option(System.getenv("BUILD_VCS_NUMBER")) getOrElse (commitId())),
  ),
  buildInfoPackage := "app",
  buildInfoOptions += BuildInfoOption.ToMap,
)

val commonSettings = Seq(
  organization := "com.gu",
  version := appVersion,
  scalaVersion := "2.13.10",
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
  debianPackageDependencies := Seq("openjdk-8-jre-headless"),
  maintainer := "Membership Dev <membership.dev@theguardian.com>",
  packageSummary := "Members Data API",
  packageDescription := """Members Data API""",
  riffRaffManifestProjectName := s"MemSub::Membership::members-data-api",
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

val `membership-common` =
  (project in file("membership-common"))
    .enablePlugins(DynamoDBLocalPlugin, SbtPgp, Sonatype)
    .settings(
      Seq(
        name := "membership-common",
        organization := "com.gu",
        scalaVersion := "2.13.10",
        scalacOptions := Seq("-feature", "-deprecation"),
        crossScalaVersions := Seq(scalaVersion.value),
        Compile / doc / sources := List(), // no docs please

        scmInfo := Some(
          ScmInfo(
            url("https://github.com/guardian/membership-common"),
            "scm:git:git@github.com:guardian/membership-common.git",
          ),
        ),
        description := "Scala library for common Guardian Membership/Subscriptions functionality.",
        licenses := Seq("Apache V2" -> url("http://www.apache.org/licenses/LICENSE-2.0.html")),
        resolvers ++= Seq(
          "Guardian Github Releases" at "https://guardian.github.io/maven/repo-releases",
          "turbolent" at "https://raw.githubusercontent.com/turbolent/mvn-repo/master/",
        ) ++ Resolver.sonatypeOssRepos("releases"),
        publishTo := {
          val nexus = "https://oss.sonatype.org/"
          if (isSnapshot.value)
            Some("snapshots" at nexus + "content/repositories/snapshots")
          else
            Some("releases" at nexus + "service/local/staging/deploy/maven2")
        },
        dynamoDBLocalVersion := "2016-04-19",
        dynamoDBLocalDownloadDir := file("dynamodb-local"),
        startDynamoDBLocal := startDynamoDBLocal.dependsOn(Test / compile).value,
        Test / testQuick := (Test / testQuick).dependsOn(startDynamoDBLocal).evaluated,
        Test / test := (Test / test).dependsOn(startDynamoDBLocal).value,
        Test / testOptions += dynamoDBLocalTestCleanup.value,
        Compile / unmanagedResourceDirectories += baseDirectory.value / "conf",
        libraryDependencies ++= MembershipCommonDependencies.dependencies,
        dependencyOverrides += MembershipCommonDependencies.jacksonDatabind,
        Global / useGpg := false,
        pgpSecretRing := file("local.secring.gpg"),
        pgpPublicRing := file("local.pubring.gpg"),
        ThisBuild / version := {
          // read from local.version (i.e. teamcity build.number), otherwise use SNAPSHOT
          val version = Try(Source.fromFile("local.version", "UTF-8").mkString.trim).toOption.getOrElse("0.1-SNAPSHOT")
          sLog.value.info(s"using version $version")
          version
        },
        ThisBuild / settingKey[String]("no need") := "There is no need to run `sbt release`, teamcity will automatically have released version 0.<build.counter> when you merged to the default branch",
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
  .dependsOn(`membership-common`)

val root = project.in(file(".")).aggregate(api)
