import sbt._
import play.sbt.PlayImport

object Dependencies {

  val awsClientVersion = "1.12.452"
  val awsClientV2Version = "2.20.38"

  val sentryLogback = "io.sentry" % "sentry-logback" % "1.7.5"
  val identityAuth = "com.gu.identity" %% "identity-auth-play" % "4.10"
  val identityTestUsers = "com.gu" %% "identity-test-users" % "0.8"
  val postgres = "org.postgresql" % "postgresql" % "42.6.0"
  val jdbc = PlayImport.jdbc
  val playWS = PlayImport.ws
  val playFilters = PlayImport.filters
  val specs2 = PlayImport.specs2 % "test"
  val scanamo = "org.scanamo" %% "scanamo" % "1.0.0-M23"
  val awsDynamo = "software.amazon.awssdk" % "dynamodb" % awsClientV2Version
  val awsSQS = "software.amazon.awssdk" % "sqs" % awsClientV2Version
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion
  val membershipCommon = "com.gu" %% "membership-common" % "0.639"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.3.7"
  val kinesis = "com.gu" % "kinesis-logback-appender" % "2.0.3"
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % "4.9"
  val anorm = "org.playframework.anorm" %% "anorm" % "2.7.0"
  val netty = "io.netty" % "netty-codec" % "4.1.87.Final"
  val nettyHttp = "io.netty" % "netty-codec-http" % "4.1.87.Final"
  val scalaXml = "org.scala-lang.modules" %% "scala-xml" % "2.1.0"
  val mockServer = "org.mock-server" % "mockserver-netty" % "5.14.0" % Test
  val mockitoScala = "org.mockito" %% "mockito-scala" % "1.17.14" % Test

  val jacksonVersion = "2.14.2"
  val jacksonDatabindVersion = "2.14.2"
  val akkaHttpCore = "com.typesafe.akka" %% "akka-http-core" % "10.2.9"
  val oktaJwtVerifierVersion = "0.5.7"
  val jackson = Seq(
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
    "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion,
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion,
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonDatabindVersion,
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonVersion,
    "com.fasterxml.jackson.module" % "jackson-module-parameter-names" % jacksonVersion,
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,
  )
  val oktaJwtVerifier = Seq(
    "com.okta.jwt" % "okta-jwt-verifier" % oktaJwtVerifierVersion,
    "com.okta.jwt" % "okta-jwt-verifier-impl" % oktaJwtVerifierVersion,
  )
  val unirest = "com.konghq" % "unirest-java" % "4.0.0-RC2" % Test

  // projects

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
    specs2.exclude("org.specs2", "specs2-mock_2.13"),
    kinesis,
    logstash,
    anorm,
    "com.amazonaws" % "aws-java-sdk-autoscaling" % awsClientVersion,
    netty,
    nettyHttp,
    "com.google.guava" % "guava" % "31.1-jre", // until https://github.com/playframework/playframework/pull/10874
    akkaHttpCore,
    unirest,
    mockServer,
    mockitoScala,
  ) ++ jackson ++ oktaJwtVerifier

  val dependencyOverrides = jackson ++ Seq(scalaXml)
  val excludeDependencies = Seq(
    ExclusionRule("com.squareup.okio", "okio"),
  )
}
