import sbt._
import play.sbt.PlayImport
import sbt.Keys.dependencyOverrides

object Dependencies {

  val awsClientVersion = "1.12.338"
  val awsClientV2Version = "2.18.13"

  val sentryLogback = "io.sentry" % "sentry-logback" % "1.7.5"
  val identityAuth = "com.gu.identity" %% "identity-auth-play" % "4.5"
  val identityTestUsers = "com.gu" %% "identity-test-users" % "0.8"
  val postgres = "org.postgresql" % "postgresql" % "42.5.0"
  val jdbc = PlayImport.jdbc
  val playWS = PlayImport.ws
  val playFilters = PlayImport.filters
  val specs2 = PlayImport.specs2 % "test"
  val scanamo = "org.scanamo" %% "scanamo" % "1.0.0-M23"
  val awsDynamo = "software.amazon.awssdk" % "dynamodb" % awsClientV2Version
  val awsSQS = "com.amazonaws" % "aws-java-sdk-sqs" % awsClientVersion
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion
  val membershipCommon = "com.gu" %% "membership-common" % "0.630"
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.3.6"
  val kinesis = "com.gu" % "kinesis-logback-appender" % "2.1.0"
  val logstash = "net.logstash.logback" % "logstash-logback-encoder" % "7.2"
  val anorm = "org.playframework.anorm" %% "anorm" % "2.7.0"
  val netty = "io.netty" % "netty-codec" % "4.1.84.Final"
  val nettyHttp = "io.netty" % "netty-codec-http" % "4.1.84.Final"
  val scalaXml = "org.scala-lang.modules" %% "scala-xml" % "2.1.0"

  val jacksonVersion = "2.14.0"
  val jacksonDatabindVersion = "2.14.0"
  val akkaHttpCore = "com.typesafe.akka" %% "akka-http-core" % "10.4.0"
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
  val awsJavaSdkAutoscaling = "com.amazonaws" % "aws-java-sdk-autoscaling" % awsClientVersion
  val akkaActorTyped = "com.typesafe.akka" %% "akka-actor-typed" % "2.7.0"
  val akkaProtobufV3 = "com.typesafe.akka" %% "akka-protobuf-v3" % "2.7.0"
  val akkaStream = "com.typesafe.akka" %% "akka-stream" % "2.7.0"
  val akkaSerializationJackson = "com.typesafe.akka" %% "akka-serialization-jackson" % "2.7.0"


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
    kinesis,
    logstash,
    anorm,
    awsJavaSdkAutoscaling,
    netty,
    nettyHttp,
    akkaHttpCore,
    akkaActorTyped,
    akkaProtobufV3,
    akkaStream,
    akkaSerializationJackson,
  ) ++ jackson ++ oktaJwtVerifier

  val depOverrides = Seq(scalaXml)

}
