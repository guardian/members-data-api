import sbt._
import play.sbt.PlayImport

object Dependencies {

  //versions
  val awsClientVersion = "1.11.226"
  //libraries
  val sentryRavenLogback = "com.getsentry.raven" % "raven-logback" % "8.0.3"
  val identityCookie = "com.gu.identity" %% "identity-cookie" % "3.80"
  val identityPlayAuth = "com.gu.identity" %% "identity-play-auth" % "2.1"
  val identityTestUsers =  "com.gu" %% "identity-test-users" % "0.6"
  val scalaUri = "com.netaporter" %% "scala-uri" % "0.4.13"
  val playWS = PlayImport.ws
  val playCache = PlayImport.cache
  val playFilters = PlayImport.filters
  val specs2 = PlayImport.specs2 % "test"
  val scanamo = "com.gu" %% "scanamo" % "0.9.3"
  val awsDynamo = "com.amazonaws" % "aws-java-sdk-dynamodb" % awsClientVersion
  val awsSQS = "com.amazonaws" % "aws-java-sdk-sqs" % awsClientVersion
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion
  val membershipCommon = "com.gu" %% "membership-common" % "0.503"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.2.7"
  val kinesis = "com.gu" % "kinesis-logback-appender" % "1.4.0"
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % "4.9"
  val jacksonCbor = "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % "2.8.7"

  //projects

  val apiDependencies = Seq(sentryRavenLogback, identityCookie, identityPlayAuth, identityTestUsers, scalaUri,
    playWS, playCache, playFilters, scanamo, awsDynamo, awsSQS, awsCloudWatch, scalaz, membershipCommon,
    specs2, kinesis, logstash, jacksonCbor)

}
