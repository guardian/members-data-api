import Dependencies._
import PlayArtifact._
import com.localytics.sbt.dynamodb.DynamoDBLocalKeys._
import play.sbt.PlayScala
import play.sbt.routes.RoutesKeys._
import sbt.Keys._
import sbt._
import sbtbuildinfo.Plugin._
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.useNativeZip

trait MembershipAttributeService {

  val appVersion = "1.0-SNAPSHOT"

  def buildInfoPlugin = buildInfoSettings ++ Seq(
    sourceGenerators in Compile <+= buildInfo,
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
    buildInfoPackage := "app"
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
    javaOptions in Test += "-Dconfig.resource=DEV.conf"
  ) ++ buildInfoPlugin

  lazy val dynamoDBLocalSettings = Seq(
    dynamoDBLocalDownloadDir := file("dynamodb-local"),
    startDynamoDBLocal <<= startDynamoDBLocal.dependsOn(compile in Test),
    test in Test <<= (test in Test).dependsOn(startDynamoDBLocal),
    testOptions in Test <+= dynamoDBLocalTestCleanup
  )

  def lib(name: String) = Project(name, file(name)).enablePlugins(PlayScala).settings(commonSettings: _*)

  def app(name: String) = lib(name)
    .settings(playArtifactDistSettings: _*)
    .settings(magentaPackageName := name)
    .settings(dynamoDBLocalSettings)
    .settings(useNativeZip)
}

object MembershipAttributeService extends Build with MembershipAttributeService {
  val api = app("membership-attribute-service")
                .settings(libraryDependencies ++= apiDependencies)
                .settings(routesGenerator := InjectedRoutesGenerator)
                .settings(
                  addCommandAlias("devrun", "run -Dconfig.resource=DEV.conf 9400"),
                  addCommandAlias("batch-load", "runMain BatchLoader")
                )


  val root = Project("root", base=file(".")).aggregate(api)
}

