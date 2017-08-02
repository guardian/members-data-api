import sbt._
import play.sbt.PlayImport

object Dependencies {

  //versions
  val awsClientVersion = "1.10.62"
  //libraries
  val sentryRavenLogback = "com.getsentry.raven" % "raven-logback" % "8.0.3"
  val identityCookie = "com.gu.identity" %% "identity-cookie" % "3.51"
  val identityPlayAuth = "com.gu.identity" %% "identity-play-auth" % "0.18"
  val identityTestUsers =  "com.gu" %% "identity-test-users" % "0.6"
  val scalaUri = "com.netaporter" %% "scala-uri" % "0.4.13"
  val playWS = PlayImport.ws
  val playCache = PlayImport.cache
  val playFilters = PlayImport.filters
  val specs2 = PlayImport.specs2 % "test"
  val scanamo = "com.gu" %% "scanamo" % "0.9.3"
  val awsWrap = "com.github.dwhjames" %% "aws-wrap" % "0.7.2"
  val awsDynamo = "com.amazonaws" % "aws-java-sdk-dynamodb" % awsClientVersion
  val awsSNS = "com.amazonaws" % "aws-java-sdk-sns" % awsClientVersion
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion
  val membershipCommon = "com.gu" %% "membership-common" % "0.433"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.1.1"
  val kinesis = "com.gu" % "kinesis-logback-appender" % "1.4.0"
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % "4.9"
  val jacksonCbor = "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % "2.8.7"

  //projects

  val apiDependencies = Seq(sentryRavenLogback, identityCookie, identityPlayAuth, identityTestUsers, scalaUri,
    playWS, playCache, playFilters, scanamo, awsWrap, awsDynamo, awsSNS, awsCloudWatch, scalaz, membershipCommon,
    specs2, kinesis, logstash, jacksonCbor)

}
