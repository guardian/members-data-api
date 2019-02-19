import sbt._
import play.sbt.PlayImport

object Dependencies {

  //versions
  val awsClientVersion = "1.11.286"
  //libraries
  val sentryLogback = "io.sentry" % "sentry-logback" % "1.7.5"
  val identityCookie = "com.gu.identity" %% "identity-cookie" % "3.99"
  val identityPlayAuth = "com.gu.identity" %% "identity-play-auth" % "2.5"
  val identityTestUsers =  "com.gu" %% "identity-test-users" % "0.7"
  val playWS = PlayImport.ws
  val playCache = PlayImport.cache
  val playFilters = PlayImport.filters
  val specs2 = PlayImport.specs2 % "test"
  val scanamo = "com.gu" %% "scanamo" % "0.9.5"
  val awsDynamo = "com.amazonaws" % "aws-java-sdk-dynamodb" % awsClientVersion
  val awsSQS = "com.amazonaws" % "aws-java-sdk-sqs" % awsClientVersion
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion
  val membershipCommon = "com.gu" %% "membership-common" % "0.533"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.2.9"
  val kinesis = "com.gu" % "kinesis-logback-appender" % "1.4.2"
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % "4.9"

  //projects

  val apiDependencies = Seq(sentryLogback, identityCookie, identityPlayAuth, identityTestUsers,
    playWS, playCache, playFilters, scanamo, awsDynamo, awsSQS, awsCloudWatch, scalaz, membershipCommon,
    specs2, kinesis, logstash)

}
