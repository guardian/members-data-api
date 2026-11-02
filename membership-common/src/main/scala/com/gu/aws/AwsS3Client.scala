package com.gu.aws

import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import play.api.libs.json.{JsValue, Json}
import scalaz.{-\/, \/, \/-}
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, GetObjectResponse}

import scala.io.Source
import scala.util.{Failure, Success, Try, Using}

object AwsS3 extends SafeLogging {

  lazy val client = S3Client.builder().credentialsProvider(CredentialsProvider).region(Region.EU_WEST_1).build()

  def fetchObject(s3Client: S3Client, request: GetObjectRequest): Try[ResponseInputStream[GetObjectResponse]] = Try(s3Client.getObject(request))

  def fetchJson(s3Client: S3Client, request: GetObjectRequest)(implicit logPrefix: LogPrefix): String \/ JsValue = {
    logger.info(s"Getting file from S3. Bucket: ${request.bucket()} | Key: ${request.key()}")
    val attempt = for {
      s3Stream <- fetchObject(s3Client, request)
      json <- Using(Source.fromInputStream(s3Stream))(source => Json.parse(source.mkString))
    } yield json
    attempt match {
      case Success(json) =>
        logger.info(s"Successfully loaded ${request.key()} from ${request.bucket()}")
        \/-(json)
      case Failure(ex) =>
        logger.error(scrub"Failed to load JSON from S3 bucket ${request.bucket()}", ex)
        -\/(s"Failed to load JSON due to $ex")
    }
  }

}
