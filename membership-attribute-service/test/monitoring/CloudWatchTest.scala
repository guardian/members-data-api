package monitoring

import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.mockito.captor.ArgCaptor
import org.specs2.mutable.Specification
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.{PutMetricDataRequest, PutMetricDataResponse, StandardUnit}

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._

class CloudWatchTest extends Specification with IdiomaticMockito with ArgumentMatchersSugar {

  "CloudWatch" should {
    "send the existing metric shape through the AWS SDK v2 client" in {
      val client = mock[CloudWatchAsyncClient]
      client.putMetricData(any[PutMetricDataRequest]) returns CompletableFuture.completedFuture(PutMetricDataResponse.builder().build())

      new CloudWatch("CODE", "members-data-api", "Accounts", client).put("request count", 1, StandardUnit.COUNT)

      val captor = ArgCaptor[PutMetricDataRequest]
      client.putMetricData(captor) wasCalled once

      val request = captor.value
      request.namespace() shouldEqual "members-data-api"
      request.metricData().size() shouldEqual 1

      val metric = request.metricData().get(0)
      metric.metricName() shouldEqual "request count"
      metric.value() shouldEqual 1
      metric.unit() shouldEqual StandardUnit.COUNT
      metric.dimensions().asScala.map(dimension => dimension.name() -> dimension.value()).toMap shouldEqual Map(
        "Stage" -> "CODE",
        "Services" -> "Accounts",
      )
    }
  }
}
