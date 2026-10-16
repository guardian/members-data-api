package services.cancellationsBus

import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.mockito.captor.ArgCaptor
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import services.zuora.rest.OrderNumber
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient
import software.amazon.awssdk.services.eventbridge.model.{PutEventsRequest, PutEventsResponse, PutEventsResultEntry}
import testdata.TestLogPrefix.testLogPrefix

import java.util.concurrent.CompletableFuture
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Try

class CancellationEventTest extends Specification with IdiomaticMockito with ArgumentMatchersSugar {

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private val orderNumber = OrderNumber.fromString("O-00005678").toOption.get
  private val event = CancellationEvent.forSelfServiceCancellation("A-S00001234", orderNumber).toOption.get

  "CancellationEvent" should {
    "serialise to the shape expected by the cancellations bus listener" in {
      Json.toJson(event) shouldEqual Json.parse("""
          |{
          |  "subscriptionNumber": "A-S00001234",
          |  "orderNumber": "O-00005678",
          |  "allowUserNotifications": true
          |}""".stripMargin)
    }

    "reject an invalid subscription number" in {
      CancellationEvent.forSelfServiceCancellation("", orderNumber).isLeft should beTrue
      CancellationEvent.forSelfServiceCancellation("A-S0000 1234", orderNumber).isLeft should beTrue
      CancellationEvent.forSelfServiceCancellation("A-S00001234\"}", orderNumber).isLeft should beTrue
    }
  }

  "PublishCancellationEventToEventBridge" should {
    "put the event on the bus with the expected source and detail type" in {
      val client = mock[EventBridgeAsyncClient]
      client.putEvents(any[PutEventsRequest]) returns CompletableFuture.completedFuture(PutEventsResponse.builder.failedEntryCount(0).build())

      Await.result(new PublishCancellationEventToEventBridge("cancellations-bus-CODE", client).publish(event), Duration.Inf)

      val captor = ArgCaptor[PutEventsRequest]
      client.putEvents(captor) wasCalled once
      val entries = captor.value.entries()
      entries.size() shouldEqual 1
      entries.get(0).eventBusName() shouldEqual "cancellations-bus-CODE"
      entries.get(0).source() shouldEqual "ec2:members-data-api"
      entries.get(0).detailType() shouldEqual "Cancellation"
      Json.parse(entries.get(0).detail()) shouldEqual Json.toJson(event)
    }

    "fail if EventBridge rejects the entry, even though the call itself succeeded" in {
      val client = mock[EventBridgeAsyncClient]
      val response = PutEventsResponse.builder
        .failedEntryCount(1)
        .entries(PutEventsResultEntry.builder.errorCode("AccessDenied").errorMessage("not allowed").build())
        .build()
      client.putEvents(any[PutEventsRequest]) returns CompletableFuture.completedFuture(response)

      Try(
        Await.result(new PublishCancellationEventToEventBridge("cancellations-bus-CODE", client).publish(event), Duration.Inf),
      ).isFailure should beTrue
    }

    "fail if the call to EventBridge fails" in {
      val client = mock[EventBridgeAsyncClient]
      client.putEvents(any[PutEventsRequest]) returns CompletableFuture.failedFuture(new RuntimeException("boom"))

      Try(
        Await.result(new PublishCancellationEventToEventBridge("cancellations-bus-CODE", client).publish(event), Duration.Inf),
      ).isFailure should beTrue
    }
  }
}
