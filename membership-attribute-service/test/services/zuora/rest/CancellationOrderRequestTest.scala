package services.zuora.rest

import com.gu.memsub.Subscription.{AccountId, SubscriptionNumber}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.okhttp.RequestRunners.HttpClient
import com.gu.zuora.ZuoraRestConfig
import com.gu.zuora.rest.SimpleClient
import io.lemonlabs.uri.typesafe.dsl._
import okhttp3.{MediaType, Protocol, Request, Response => OkResponse, ResponseBody}
import okio.Buffer
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import scalaz.{-\/, \/}
import scalaz.std.scalaFuture._
import services.zuora.rest.ZuoraRestService.{CancellationOrderRequest, OrderResponse}
import testdata.TestLogPrefix.testLogPrefix

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class CancellationOrderRequestTest extends Specification {

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private val accountId = AccountId("8a129e8d9d1c5d0f019d1d5e6a8a1234")
  private val subscriptionNumber = SubscriptionNumber("A-S01234567")
  private val orderDate = new LocalDate(2026, 8, 26)
  private val cancellationEffectiveDate = new LocalDate(2026, 9, 10)

  "CancellationOrderRequest" should {
    "renew the term before cancelling when the paid period extends past the term" in {
      val request = CancellationOrderRequest.forSubscription(
        accountId,
        subscriptionNumber,
        orderDate,
        cancellationEffectiveDate,
        needsTermRenewal = true,
      )

      Json.toJson(request) shouldEqual Json.parse("""{
        "orderDate": "2026-08-26",
        "existingAccountId": "8a129e8d9d1c5d0f019d1d5e6a8a1234",
        "subscriptions": [{
          "subscriptionNumber": "A-S01234567",
          "orderActions": [
            {
              "type": "RenewSubscription",
              "triggerDates": [
                {"name": "ContractEffective", "triggerDate": "2026-08-26"},
                {"name": "ServiceActivation", "triggerDate": "2026-08-26"},
                {"name": "CustomerAcceptance", "triggerDate": "2026-08-26"}
              ]
            },
            {
              "type": "CancelSubscription",
              "triggerDates": [{"name": "ContractEffective", "triggerDate": "2026-09-10"}],
              "cancelSubscription": {
                "cancellationPolicy": "SpecificDate",
                "cancellationEffectiveDate": "2026-09-10"
              }
            }
          ]
        }],
        "processingOptions": {"runBilling": false, "collectPayment": false}
      }""")
    }

    "cancel immediately without a renewal when there is no paid period" in {
      val request = CancellationOrderRequest.forSubscription(
        accountId,
        subscriptionNumber,
        orderDate,
        orderDate,
        needsTermRenewal = false,
      )

      ((Json.toJson(request) \ "subscriptions").as[Seq[play.api.libs.json.JsValue]].head \ "orderActions").get shouldEqual Json.arr(
        Json.obj(
          "type" -> "CancelSubscription",
          "triggerDates" -> Json.arr(Json.obj("name" -> "ContractEffective", "triggerDate" -> "2026-08-26")),
          "cancelSubscription" -> Json.obj(
            "cancellationPolicy" -> "SpecificDate",
            "cancellationEffectiveDate" -> "2026-08-26",
          ),
        ),
      )
    }
  }

  "OrderResponse" should {
    "only accept a completed Zuora order" in {
      OrderResponse.completed(OrderResponse(success = true, status = Some("Completed"))) shouldEqual \/.right(())
      OrderResponse.completed(OrderResponse(success = true, status = Some("Processing"))) shouldEqual -\/(
        "Zuora order completed with success = true and status = Processing",
      )
      OrderResponse.completed(OrderResponse(success = false, status = None)) shouldEqual -\/(
        "Zuora order completed with success = false and status = missing",
      )
    }
  }

  "SimpleClientZuoraRestService" should {
    "post the cancellation order and only return once Zuora completes it" in {
      val ordersClient = new RespondingClient("""{"success": true, "status": "Completed"}""")
      val zuoraConfig = ZuoraRestConfig("CODE", "https://example.com/v1", "user", "password")
      val restClient = SimpleClient(zuoraConfig, ordersClient)
      val service = new SimpleClientZuoraRestService(restClient, () => orderDate)

      Await.result(
        service.cancelSubscription(
          subscriptionNumber,
          accountId,
          new LocalDate(2026, 9, 1),
          Some(cancellationEffectiveDate),
        ),
        Duration.Inf,
      ) shouldEqual \/.right(())

      ordersClient.request.url().toString() shouldEqual "https://example.com/v1/orders"
      ordersClient.request.method() shouldEqual "POST"
      val requestBody = new Buffer()
      ordersClient.request.body().writeTo(requestBody)
      Json.parse(requestBody.readUtf8()) shouldEqual Json.toJson(
        CancellationOrderRequest.forSubscription(
          accountId,
          subscriptionNumber,
          orderDate,
          cancellationEffectiveDate,
          needsTermRenewal = true,
        ),
      )
    }
  }

  private class RespondingClient(body: String) extends HttpClient[Future] {
    var request: Request = _

    override def execute(in: Request)(implicit logPrefix: LogPrefix): Future[OkResponse] = {
      request = in
      Future.successful(
        new OkResponse.Builder()
          .body(ResponseBody.create(body, MediaType.parse("application/json")))
          .protocol(Protocol.HTTP_2)
          .request(in)
          .message("test")
          .code(200)
          .build(),
      )
    }
  }
}
