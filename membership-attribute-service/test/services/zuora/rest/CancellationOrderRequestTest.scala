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
import play.api.libs.json.{JsSuccess, Json}
import scalaz.\/
import scalaz.std.scalaFuture._
import services.zuora.rest.SimpleClientZuoraRestService.OrderResponse
import services.zuora.rest.ZuoraRestService.CancellationOrderRequest
import testdata.TestLogPrefix.testLogPrefix

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class CancellationOrderRequestTest extends Specification {

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private val accountId = AccountId("8a129e8d9d1c5d0f019d1d5e6a8a1234")
  private val subscriptionNumber = SubscriptionNumber("A-S01234567")
  private val orderDate = new LocalDate(2026, 8, 26)
  private val cancellationEffectiveDate = new LocalDate(2026, 9, 10)
  private val orderNumber = OrderNumber.fromString("O-00005678").toOption.get

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
    "read a completed order with its order number" in {
      Json.parse("""{"success": true, "status": "Completed", "orderNumber": "O-00005678"}""").validate[OrderResponse] shouldEqual
        JsSuccess(OrderResponse(orderNumber))
    }

    "fail to read a completed order with a missing or malformed order number" in {
      Json.parse("""{"success": true, "status": "Completed"}""").validate[OrderResponse].isError should beTrue
      Json.parse("""{"success": true, "status": "Completed", "orderNumber": "O 123!"}""").validate[OrderResponse].isError should beTrue
      Json.parse("""{"success": true, "status": "Completed", "orderNumber": 123}""").validate[OrderResponse].isError should beTrue
    }

    "fail to read an order that is not completed, even if it has an order number" in {
      Json.parse("""{"success": false}""").validate[OrderResponse].isError should beTrue
      Json.parse("""{"success": false, "status": "Completed", "orderNumber": "O-00005678"}""").validate[OrderResponse].isError should beTrue
      Json.parse("""{"success": true, "status": "Processing", "orderNumber": "O-00005678"}""").validate[OrderResponse].isError should beTrue
      Json.parse("""{"success": true, "orderNumber": "O-00005678"}""").validate[OrderResponse].isError should beTrue
    }
  }

  "OrderNumber" should {
    "only accept letters, digits and hyphens" in {
      OrderNumber.fromString("O-00005678").map(_.value) shouldEqual Right("O-00005678")
      OrderNumber.fromString("").isLeft should beTrue
      OrderNumber.fromString("O_00005678").isLeft should beTrue
      OrderNumber.fromString("O-0000 5678").isLeft should beTrue
    }
  }

  "SimpleClientZuoraRestService" should {
    "post the cancellation order and only return once Zuora completes it" in {
      val ordersClient = new RespondingClient("""{"success": true, "status": "Completed", "orderNumber": "O-00005678"}""")
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
      ) shouldEqual \/.right(orderNumber)

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

    "return an error if Zuora does not complete the cancellation order" in {
      val ordersClient = new RespondingClient("""{"success": true, "status": "Processing", "orderNumber": "O-00005678"}""")
      val zuoraConfig = ZuoraRestConfig("CODE", "https://example.com/v1", "user", "password")
      val service = new SimpleClientZuoraRestService(SimpleClient(zuoraConfig, ordersClient), () => orderDate)

      val result = Await.result(
        service.cancelSubscription(subscriptionNumber, accountId, new LocalDate(2026, 9, 1), Some(cancellationEffectiveDate)),
        Duration.Inf,
      )

      result.isLeft should beTrue
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
