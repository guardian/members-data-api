package com.gu.aws

import com.gu.monitoring.SafeLogger.LogPrefix
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import scalaz.\/-
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, GetObjectResponse}

import java.io.ByteArrayInputStream
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.nio.charset.StandardCharsets

class AwsS3ClientTest extends Specification {

  implicit private val logPrefix: LogPrefix = LogPrefix.noLogPrefix

  "AwsS3" should {
    "parse an S3 object as JSON and close its stream" in {
      val request = GetObjectRequest.builder().bucket("catalog-bucket").key("catalog.json").build()
      val input = new CloseTrackingInputStream("{\"catalog\":true}")
      val client = s3ClientReturning(request, new ResponseInputStream(GetObjectResponse.builder().build(), input))

      AwsS3.fetchJson(client, request) mustEqual \/-(Json.obj("catalog" -> true))
      input.closed must beTrue
    }

    "close the S3 stream when the object is not valid JSON" in {
      val request = GetObjectRequest.builder().bucket("catalog-bucket").key("catalog.json").build()
      val input = new CloseTrackingInputStream("not-json")
      val client = s3ClientReturning(request, new ResponseInputStream(GetObjectResponse.builder().build(), input))

      AwsS3.fetchJson(client, request).isLeft must beTrue
      input.closed must beTrue
    }
  }

  private def s3ClientReturning(expectedRequest: GetObjectRequest, response: ResponseInputStream[GetObjectResponse]): S3Client =
    Proxy
      .newProxyInstance(
        classOf[S3Client].getClassLoader,
        Array(classOf[S3Client]),
        new InvocationHandler {
          override def invoke(proxy: Any, method: Method, arguments: Array[AnyRef]): AnyRef =
            if (method.getName == "getObject" && arguments.toSeq == Seq(expectedRequest)) response
            else throw new UnsupportedOperationException(method.toString)
        },
      )
      .asInstanceOf[S3Client]
}

private class CloseTrackingInputStream(contents: String) extends ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8)) {
  var closed = false

  override def close(): Unit = {
    closed = true
    super.close()
  }
}
