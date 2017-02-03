import sbt._
import play.sbt.PlayImport

object Dependencies {

  //versions
  val awsClientVersion = "1.10.62"
  //libraries
  val sentryRavenLogback = "net.kencochrane.raven" % "raven-logback" % "6.0.0"
  val identityCookie = "com.gu.identity" %% "identity-cookie" % "3.51"
  val identityPlayAuth = "com.gu.identity" %% "identity-play-auth" % "0.18"
  val identityTestUsers =  "com.gu" %% "identity-test-users" % "0.6"
  val scalaUri = "com.netaporter" %% "scala-uri" % "0.4.13"
  val playWS = PlayImport.ws
  val playCache = PlayImport.cache
  val playFilters = PlayImport.filters
  val specs2 = PlayImport.specs2 % "test"
  val scanamo = "com.gu" %% "scanamo" % "0.4.0"
  val awsWrap = "com.github.dwhjames" %% "aws-wrap" % "0.7.2"
  val awsDynamo = "com.amazonaws" % "aws-java-sdk-dynamodb" % awsClientVersion
  val awsSNS = "com.amazonaws" % "aws-java-sdk-sns" % awsClientVersion
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion
  val membershipCommon = "com.gu" %% "membership-common" % "0.346"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.1.1"

  //projects

  val apiDependencies = Seq(sentryRavenLogback, identityCookie, identityPlayAuth, identityTestUsers, scalaUri,
    playWS, playCache, playFilters, scanamo, awsWrap, awsDynamo, awsSNS, awsCloudWatch, scalaz, membershipCommon,
    specs2)

}
