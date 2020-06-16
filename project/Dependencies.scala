import sbt._
import play.sbt.PlayImport

object Dependencies {

  //versions
  val awsVersion = "1.11.420"
  //libraries
  val sentryLogback = "io.sentry" % "sentry-logback" % "1.7.5"
  val identityAuth = "com.gu.identity" %% "identity-auth-play" % "3.195"
  val identityTestUsers =  "com.gu" %% "identity-test-users" % "0.7"
  val postgres =  "org.postgresql" % "postgresql" % "42.2.1"
  val jdbc = PlayImport.jdbc
  val playWS = PlayImport.ws
  val playFilters = PlayImport.filters
  val specs2 = PlayImport.specs2 % "test"
  val scanamo = "com.gu" %% "scanamo" % "1.0.0-M8"
  val awsDynamo = "com.amazonaws" % "aws-java-sdk-dynamodb" % awsVersion
  val awsSQS = "com.amazonaws" % "aws-java-sdk-sqs" % awsVersion
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsVersion
  val membershipCommon = "com.gu" %% "membership-common" % "0.572"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.2.9"
  val kinesis = "com.gu" % "kinesis-logback-appender" % "1.4.2"
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % "4.9"
  val anorm = "org.playframework.anorm" %% "anorm" % "2.6.0"

  //projects

  val apiDependencies = Seq(jdbc, postgres, sentryLogback, identityAuth, identityTestUsers,
    playWS, playFilters, scanamo, awsDynamo, awsSQS, awsCloudWatch, scalaz, membershipCommon,
    specs2, kinesis, logstash, anorm,
    "com.amazonaws" % "aws-java-sdk-s3" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-ec2" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-cloudformation" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-ssm" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-acm" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-route53" % awsVersion,
    "com.amazonaws" % "aws-java-sdk-autoscaling" % awsVersion
  )

}
