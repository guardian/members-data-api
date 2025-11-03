import sbt.*
import play.sbt.PlayImport

object Dependencies {

  val awsClientVersion = "1.12.667"
  val awsClientV2Version = "2.35.10"

  val sentryLogback = "io.sentry" % "sentry-logback" % "7.2.0"
  val identityAuth = "com.gu.identity" %% "identity-auth-play" % "4.37.0"
  val identityTestUsers = "com.gu" %% "identity-test-users" % "0.10.2"
  val postgres = "org.postgresql" % "postgresql" % "42.7.2"
  val jdbc = PlayImport.jdbc
  val playWS = PlayImport.ws
  val playFilters = PlayImport.filters
  val specs2 = (PlayImport.specs2 % "test")
    .exclude("net.sourceforge.htmlunit", "htmlunit") // Exclude vulnerable version, replace with org.htmlunit below
  val scanamo = "org.scanamo" %% "scanamo" % "4.0.0"
  val awsDynamo = "software.amazon.awssdk" % "dynamodb" % awsClientV2Version
  val awsSQS = "software.amazon.awssdk" % "sqs" % awsClientV2Version
  val awsCloudWatch = "com.amazonaws" % "aws-java-sdk-cloudwatch" % awsClientVersion
  val scalaz = "org.scalaz" %% "scalaz-core" % "7.3.8"
  val anorm = "org.playframework.anorm" %% "anorm" % "2.7.0"
  val netty = "io.netty" % "netty-codec" % "4.1.118.Final"
  val nettyHttp = "io.netty" % "netty-codec-http" % "4.1.118.Final"
  val scalaXml = "org.scala-lang.modules" %% "scala-xml" % "2.1.0"
  val htmlUnit = "org.htmlunit" % "htmlunit" % "3.11.0" // Override to fix CVE-2023-2798 (RCE) - requires 3.0.0+
  val mockServer = "org.mock-server" % "mockserver-netty" % "5.14.0" % Test
  val mockitoScala = "org.mockito" %% "mockito-scala" % "1.17.14" % Test
  val logback = "ch.qos.logback" % "logback-classic" % "1.4.14"

  val jacksonVersion = "2.15.4"
  val akkaHttpCore = "com.typesafe.akka" %% "akka-http-core" % "10.2.9"
  val oktaJwtVerifierVersion = "0.5.7"
  val jackson = Seq(
    "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
    "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion,
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion,
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
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
    specs2.exclude("org.specs2", "specs2-mock_2.13"),
    logback,
    anorm,
    netty,
    nettyHttp,
    htmlUnit % Test, // Safe version to replace excluded net.sourceforge.htmlunit:htmlunit
    "com.google.guava" % "guava" % "32.1.3-jre", // until https://github.com/playframework/playframework/pull/10874
    unirest,
    mockServer,
    mockitoScala,
  ) ++ jackson ++ oktaJwtVerifier

  val dependencyOverrides = jackson ++ Seq(scalaXml)
  val excludeDependencies = Seq(
    ExclusionRule("com.squareup.okio", "okio"),
    ExclusionRule("net.sourceforge.htmlunit", "htmlunit"), // Block vulnerable version from all transitive deps
  )
}
