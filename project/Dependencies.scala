import sbt._
import play.sbt.PlayImport

object Dependencies {

  val awsClientVersion = "1.11.1012"

  val sentryLogback = "io.sentry" % "sentry-logback" % "1.7.5"
  val identityAuth = "com.gu.identity" %% "identity-auth-play" % "3.235"
  val identityTestUsers = "com.gu" %% "identity-test-users" % "0.7"
  val postgres = "org.postgresql" % "postgresql" % "42.2.20"
  val jdbc = PlayImport.jdbc
  val playWS = PlayImport.ws
  val playFilters = PlayImport.filters
  val specs2 = PlayImport.specs2 % "test"
  val scanamo = "com.gu" %% "scanamo" % "1.0.0-M8"
  val awsDynamo = "com.amazonaws" % "aws-java-sdk-dynamodb" % awsClientVersion
  val awsSQS = "com.amazonaws" % "aws-java-sdk-sqs" % awsClientVersion
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion
  val membershipCommon = "com.gu" %% "membership-common" % "0.592"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.2.31"
  val kinesis = "com.gu" % "kinesis-logback-appender" % "2.0.2"
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % "4.9"
  val anorm = "org.playframework.anorm" %% "anorm" % "2.6.10"
  val netty = "io.netty" % "netty-codec" % "4.1.59.Final"
  val nettyHttp = "io.netty" % "netty-codec-http" % "4.1.59.Final"

  //projects

  val apiDependencies = Seq(
    jdbc,
    postgres,
    sentryLogback,
    identityAuth,
    identityTestUsers,
    playWS,
    playFilters,
    scanamo,
    awsDynamo,
    awsSQS,
    awsCloudWatch,
    scalaz,
    membershipCommon,
    specs2,
    kinesis,
    logstash,
    anorm,
    "com.amazonaws" % "aws-java-sdk-autoscaling" % awsClientVersion,
    "com.fasterxml.jackson.core" % "jackson-databind" % "2.9.10.8",
    netty,
    nettyHttp,
  )

}
