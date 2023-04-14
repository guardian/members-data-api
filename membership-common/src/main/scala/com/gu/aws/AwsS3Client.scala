package com.gu.aws

import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import com.amazonaws.services.s3.model.{GetObjectRequest, S3ObjectInputStream}
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import play.api.libs.json.{JsValue, Json}
import scala.io.Source
import scala.util.{Failure, Success, Try}
import scalaz.{-\/, \/, \/-}

object AwsS3 {

  lazy val client = AmazonS3Client.builder.withCredentials(CredentialsProvider).build()

  def fetchObject(s3Client: AmazonS3, request: GetObjectRequest): Try[S3ObjectInputStream] = Try(s3Client.getObject(request).getObjectContent)

  def fetchJson(s3Client: AmazonS3, request: GetObjectRequest): String \/ JsValue = {
    SafeLogger.info(s"Getting file from S3. Bucket: ${request.getBucketName} | Key: ${request.getKey}")
    val attempt = for {
      s3Stream <- fetchObject(s3Client, request)
      json <- Try(Json.parse(Source.fromInputStream(s3Stream).mkString))
      _ <- Try(s3Stream.close())
    } yield json
    attempt match {
      case Success(json) =>
        SafeLogger.info(s"Successfully loaded ${request.getKey} from ${request.getBucketName}")
        \/-(json)
      case Failure(ex) =>
        SafeLogger.error(scrub"Failed to load JSON from S3 bucket ${request.getBucketName}", ex)
        -\/(s"Failed to load JSON due to $ex")
    }
  }

}
