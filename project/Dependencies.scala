import sbt._
import play.sbt.PlayImport

object Dependencies {

  //versions
  val awsClientVersion = "1.11.286"
  //libraries
  val sentryLogback = "io.sentry" % "sentry-logback" % "1.7.5"
  val identityAuth = "com.gu.identity" %% "identity-auth-play" % "3.184"
  val identityTestUsers =  "com.gu" %% "identity-test-users" % "0.7"
  val postgres =  "org.postgresql" % "postgresql" % "42.2.1"
  val jdbc = PlayImport.jdbc
  val playWS = PlayImport.ws
  val playCache = PlayImport.cache
  val playFilters = PlayImport.filters
  val specs2 = PlayImport.specs2 % "test"
  val scanamo = "com.gu" %% "scanamo" % "1.0.0-M8"
  val awsDynamo = "com.amazonaws" % "aws-java-sdk-dynamodb" % awsClientVersion
  val awsSQS = "com.amazonaws" % "aws-java-sdk-sqs" % awsClientVersion
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion
  val membershipCommon = "com.gu" %% "membership-common" % "0.554"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.2.9"
  val kinesis = "com.gu" % "kinesis-logback-appender" % "1.4.2"
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % "4.9"
  val anorm = "org.playframework.anorm" %% "anorm" % "2.6.0"

  //projects

  val apiDependencies = Seq(jdbc, postgres, sentryLogback, identityAuth, identityTestUsers,
    playWS, playCache, playFilters, scanamo, awsDynamo, awsSQS, awsCloudWatch, scalaz, membershipCommon,
    specs2, kinesis, logstash, anorm)

}
