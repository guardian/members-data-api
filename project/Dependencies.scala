import play.sbt.PlayImport
<<<<<<< HEAD
<<<<<<< HEAD
import sbt._
=======
import sbt.Keys.dependencyOverrides
>>>>>>> 31b7aa0 (Revert "Revert "Upgrade SBT and dependencies."")
=======
>>>>>>> 52ce6b0 (Revert "Revert "Revert "Upgrade SBT and dependencies.""")

object Dependencies {

  val awsClientVersion = "1.11.1022"
  val awsClientV2Version = "2.16.86"

  val sentryLogback = "io.sentry" % "sentry-logback" % "1.7.5"
  val identityAuth = "com.gu.identity" %% "identity-auth-play" % "4.5"
  val identityTestUsers = "com.gu" %% "identity-test-users" % "0.8"
  val postgres = "org.postgresql" % "postgresql" % "42.3.3"
  val jdbc = PlayImport.jdbc
  val playWS = PlayImport.ws
  val playFilters = PlayImport.filters
  val guice = PlayImport.guice
  val specs2 = PlayImport.specs2 % Test
  val scanamo = "org.scanamo" %% "scanamo" % "1.0.0-M23"
  val awsDynamo = "software.amazon.awssdk" % "dynamodb" % awsClientV2Version
  val awsSQS = "com.amazonaws" % "aws-java-sdk-sqs" % awsClientVersion
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion
  val membershipCommon = "com.gu" %% "membership-common" % "0.631"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.3.6"
  val kinesis = "com.gu" % "kinesis-logback-appender" % "2.1.0"
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % "7.2"
  val anorm = "org.playframework.anorm" %% "anorm" % "2.7.0"
  val netty = "io.netty" % "netty-codec" % "4.1.84.Final"
  val nettyHttp = "io.netty" % "netty-codec-http" % "4.1.84.Final"
  val scalaXml = "org.scala-lang.modules" %% "scala-xml" % "2.1.0"
  val mockServer = "org.mock-server" % "mockserver-netty" % "5.14.0" % Test

  val jacksonVersion = "2.13.2"
  val jacksonDatabindVersion = "2.13.2.2"
  val akkaHttpCore = "com.typesafe.akka" %% "akka-http-core" % "10.2.9"
  val oktaJwtVerifierVersion = "0.5.5"
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
  val awsJavaSdkAutoscaling = "com.amazonaws" % "aws-java-sdk-autoscaling" % awsClientVersion
  val akkaActorTyped = "com.typesafe.akka" %% "akka-actor-typed" % "2.7.0"
  val akkaProtobufV3 = "com.typesafe.akka" %% "akka-protobuf-v3" % "2.7.0"
  val akkaStream = "com.typesafe.akka" %% "akka-stream" % "2.7.0"
  val akkaSerializationJackson = "com.typesafe.akka" %% "akka-serialization-jackson" % "2.7.0"

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
    specs2,
    guice,
    kinesis,
    logstash,
    anorm,
    "com.amazonaws" % "aws-java-sdk-autoscaling" % awsClientVersion,
    netty,
    nettyHttp,
    "com.google.guava" % "guava" % "30.1.1-jre", // until https://github.com/playframework/playframework/pull/10874
    akkaHttpCore,
    akkaActorTyped,
    akkaProtobufV3,
    akkaStream,
    akkaSerializationJackson,
    unirest,
    mockServer
  ) ++ jackson ++ oktaJwtVerifier

  val depOverrides = jackson

}
