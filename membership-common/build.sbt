import com.localytics.sbt.dynamodb.DynamoDBLocalKeys._
import Dependencies._
import sbt.Keys.version

import scala.io.Source
import scala.util.Try

name := "membership-common"

organization := "com.gu"

scalaVersion := "2.13.10"

scalacOptions := Seq("-feature", "-deprecation")

crossScalaVersions := Seq(scalaVersion.value)

Compile / doc / sources := List() // no docs please

scmInfo := Some(ScmInfo(
  url("https://github.com/guardian/membership-common"),
  "scm:git:git@github.com:guardian/membership-common.git"
))

description := "Scala library for common Guardian Membership/Subscriptions functionality."

licenses := Seq("Apache V2" -> url("http://www.apache.org/licenses/LICENSE-2.0.html"))

resolvers ++= Seq(
  "Guardian Github Releases" at "https://guardian.github.io/maven/repo-releases",
  "turbolent" at "https://raw.githubusercontent.com/turbolent/mvn-repo/master/",
) ++ Resolver.sonatypeOssRepos("releases")

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

dynamoDBLocalVersion := "2016-04-19"
dynamoDBLocalDownloadDir := file("dynamodb-local")
startDynamoDBLocal := {startDynamoDBLocal.dependsOn(Test / compile).value}
Test / testQuick := {(Test / testQuick).dependsOn(startDynamoDBLocal).evaluated}
Test / test := {(Test / test).dependsOn(startDynamoDBLocal).value}
Test / testOptions += {dynamoDBLocalTestCleanup.value}

Compile / unmanagedResourceDirectories += baseDirectory.value / "conf"

dependencyCheckSuppressionFiles := Seq(file("dependencyCheckSuppression.xml"))

libraryDependencies ++= Seq(
  scalaUri,
  nscalaTime,
  akkaActor,
  supportInternationalisation,
  playJson,
  playJsonJoda,
  specs2 % "test",
  specs2Mock % "test",
  specs2Matchers % "test",
  specs2MatchersExtra % "test",
  scalaTest % "test",
  diff % "test",
  scalaLogging,
  awsCloudWatch,
  okHttp,
  scalaz,
  libPhoneNumber,
  dynamoDB,
  scalaXml
)

dependencyOverrides += jacksonDatabind

lazy val root = (project in file("."))

Global / useGpg := false

pgpSecretRing := file("local.secring.gpg")
pgpPublicRing := file("local.pubring.gpg")

ThisBuild / version := {
  // read from local.version (i.e. teamcity build.number), otherwise use SNAPSHOT
  val version = Try(Source.fromFile("local.version", "UTF-8").mkString.trim).toOption.getOrElse("0.1-SNAPSHOT")
  sLog.value.info(s"using version $version")
  version
}

val release = settingKey[String]("no need")
ThisBuild / release := {
  "There is no need to run `sbt release`, teamcity will automatically have released version 0.<build.counter> when you merged to the default branch"
}
