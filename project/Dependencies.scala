import sbt._
import play.sbt.PlayImport

object Dependencies {

  //versions
  val awsClientVersion = "1.9.30"
  //libraries
  val sentryRavenLogback = "net.kencochrane.raven" % "raven-logback" % "6.0.0"
  val identityCookie = "com.gu.identity" %% "identity-cookie" % "3.44"
  val identityPlayAuth = "com.gu.identity" %% "identity-play-auth" % "0.12"
  val identityTestUsers =  "com.gu" %% "identity-test-users" % "0.5"
  val scalaUri = "com.netaporter" %% "scala-uri" % "0.4.6"
  val playWS = PlayImport.ws
  val playCache = PlayImport.cache
  val playFilters = PlayImport.filters
  val scalaTest =  "org.scalatestplus" %% "play" % "1.4.0-M3" % "test"
  val specs2 = PlayImport.specs2 % "test"
  val awsWrap = "com.github.dwhjames" %% "aws-wrap" % "0.7.2"
  val awsDynamo = "com.amazonaws" % "aws-java-sdk-dynamodb" % "1.9.31"
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % "1.9.31"
  val membershipCommon = "com.gu" %% "membership-common" % "0.105"
  val macWireMacros = "com.softwaremill.macwire" %% "macros" % "2.1.0" % "provided"
  val macWireUtil = "com.softwaremill.macwire" %% "util" % "2.1.0"
  val macwireProxy = "com.softwaremill.macwire" %% "proxy" % "2.1.0"


  val scalaz = "org.scalaz" %% "scalaz-core" % "7.1.1"

  //projects

  val apiDependencies = Seq(sentryRavenLogback, identityCookie, identityPlayAuth, identityTestUsers, scalaUri,
    playWS, playCache, playFilters, awsWrap, awsDynamo, awsCloudWatch, scalaz, membershipCommon, macWireMacros, macWireUtil, macwireProxy,
    specs2, scalaTest)

}
