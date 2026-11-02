package com.gu.monitoring

import com.gu.monitoring.SafeLogger.LogPrefix
import org.specs2.mutable.Specification
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.{PutMetricDataRequest, PutMetricDataResponse, StandardUnit}

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._

class CloudWatchTest extends Specification {

  implicit private val logPrefix: LogPrefix = LogPrefix.noLogPrefix

  "CloudWatch" should {
    "send the existing metric shape through the AWS SDK v2 client" in {
      val capturedRequest = new AtomicReference[PutMetricDataRequest]()
      val cloudWatch = new TestCloudWatch(cloudWatchClientCapturing(capturedRequest))
      CloudWatchHealth.hasPushedMetricSuccessfully = false

      cloudWatch.put("request-count", 1, "GET")

      val request = capturedRequest.get()
      request.namespace() mustEqual "members-data-api"
      request.metricData().size() mustEqual 1

      val metric = request.metricData().get(0)
      metric.metricName() mustEqual "request-count"
      metric.value() mustEqual 1
      metric.unit() mustEqual StandardUnit.COUNT
      metric.dimensions().asScala.map(dimension => dimension.name() -> dimension.value()).toMap mustEqual Map(
        "Stage" -> "CODE",
        "Services" -> "Salesforce",
        "ResponseMethod" -> "GET",
      )
      CloudWatchHealth.hasPushedMetricSuccessfully must beTrue
    }
  }

  private def cloudWatchClientCapturing(capturedRequest: AtomicReference[PutMetricDataRequest]): CloudWatchAsyncClient =
    Proxy
      .newProxyInstance(
        classOf[CloudWatchAsyncClient].getClassLoader,
        Array(classOf[CloudWatchAsyncClient]),
        new InvocationHandler {
          override def invoke(proxy: Any, method: Method, arguments: Array[AnyRef]): AnyRef =
            if (method.getName == "putMetricData") {
              capturedRequest.set(arguments(0).asInstanceOf[PutMetricDataRequest])
              CompletableFuture.completedFuture(PutMetricDataResponse.builder().build())
            } else throw new UnsupportedOperationException(method.toString)
        },
      )
      .asInstanceOf[CloudWatchAsyncClient]
}

private class TestCloudWatch(client: CloudWatchAsyncClient) extends CloudWatch {
  override val stage = "CODE"
  override val application = "members-data-api"
  override val service = "Salesforce"
  override protected def cloudWatchClient: CloudWatchAsyncClient = client
}
