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
  val postgres =  "org.postgresql" % "postgresql" % "42.2.1"
  val jdbc = PlayImport.jdbc
  val playWS = PlayImport.ws
  val playCache = PlayImport.cache
  val playFilters = PlayImport.filters
  val specs2 = PlayImport.specs2 % "test"
  val scanamo = "com.gu" %% "scanamo" % "0.9.5"
  val awsDynamo = "com.amazonaws" % "aws-java-sdk-dynamodb" % awsClientVersion
  val awsSQS = "com.amazonaws" % "aws-java-sdk-sqs" % awsClientVersion
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion
  val membershipCommon = "com.gu" %% "membership-common" % "0.547"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.2.9"
  val kinesis = "com.gu" % "kinesis-logback-appender" % "1.4.2"
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % "4.9"
  val enumeratum = "com.beachape" %% "enumeratum" % "1.5.12"
  val enumeratumCirce = "com.beachape" %% "enumeratum-circe" % "1.5.12"
  val anorm = "org.playframework.anorm" %% "anorm" % "2.6.0"
  val circeCore = "io.circe" %% "circe-core" % "0.9.1"
  val circeGeneric = "io.circe" %% "circe-generic" % "0.9.1"
  val circeParse = "io.circe" %% "circe-parser" % "0.9.1"



  //projects

  val apiDependencies = Seq(jdbc, postgres, sentryLogback, identityCookie, identityPlayAuth, identityTestUsers,
    playWS, playCache, playFilters, scanamo, awsDynamo, awsSQS, awsCloudWatch, scalaz, membershipCommon,
    specs2, kinesis, logstash, enumeratum, enumeratumCirce, anorm, circeGeneric, circeParse )

}
