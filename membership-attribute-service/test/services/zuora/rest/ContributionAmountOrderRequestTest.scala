package services.zuora.rest

import com.gu.memsub.Subscription.{AccountId, RatePlanId, SubscriptionNumber, SubscriptionRatePlanChargeNumber}
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
import scalaz.\/
import scalaz.std.scalaFuture._
import services.zuora.rest.SimpleClientZuoraRestService.OrderResponse
import services.zuora.rest.ZuoraRestService.ContributionAmountOrderRequest
import testdata.TestLogPrefix.testLogPrefix

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class ContributionAmountOrderRequestTest extends Specification {

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  private val accountId = AccountId("8a129e8d9d1c5d0f019d1d5e6a8a1234")
  private val subscriptionNumber = SubscriptionNumber("A-S01234567")
  private val ratePlanId = RatePlanId("8a129e8d9d1c5d0f019d1d5e6a8a1234")
  private val chargeNumber = SubscriptionRatePlanChargeNumber("C-00001234")
  private val orderDate = new LocalDate(2026, 9, 1)
  private val applyFromDate = new LocalDate(2026, 9, 10)
  private val reason = "User updated contribution via self-service MMA. Amount changed from £10.00 to £15.00 effective from 2026-09-10"

  "ContributionAmountOrderRequest" should {
    "keep the order date separate from the contribution effective date" in {
      val request = ContributionAmountOrderRequest.forSubscription(
        accountId,
        subscriptionNumber,
        ratePlanId,
        chargeNumber,
        15.0,
        reason,
        orderDate,
        applyFromDate,
      )

      Json.toJson(request) shouldEqual Json.parse("""{
        "orderDate": "2026-09-01",
        "existingAccountId": "8a129e8d9d1c5d0f019d1d5e6a8a1234",
        "subscriptions": [{
          "subscriptionNumber": "A-S01234567",
          "orderActions": [{
            "type": "UpdateProduct",
            "triggerDates": [
              {"name": "ContractEffective", "triggerDate": "2026-09-10"},
              {"name": "ServiceActivation", "triggerDate": "2026-09-10"},
              {"name": "CustomerAcceptance", "triggerDate": "2026-09-10"}
            ],
            "changeReason": "User updated contribution via self-service MMA. Amount changed from £10.00 to £15.00 effective from 2026-09-10",
            "updateProduct": {
              "ratePlanId": "8a129e8d9d1c5d0f019d1d5e6a8a1234",
              "chargeUpdates": [{
                "chargeNumber": "C-00001234",
                "pricing": {"recurringFlatFee": {"listPrice": 15.0}}
              }]
            }
          }]
        }],
        "processingOptions": {"runBilling": false, "collectPayment": false}
      }""")
    }
  }

  "SimpleClientZuoraRestService" should {
    "post the contribution amount order and only return once Zuora completes it" in {
      val ordersClient = new RespondingClient("""{"success": true, "status": "Completed"}""")
      val zuoraConfig = ZuoraRestConfig("CODE", "https://example.com/v1", "user", "password")
      val service = new SimpleClientZuoraRestService(SimpleClient(zuoraConfig, ordersClient), () => orderDate)

      Await.result(
        service.updateChargeAmount(
          subscriptionNumber,
          accountId,
          chargeNumber,
          ratePlanId,
          15.0,
          reason,
          applyFromDate,
        ),
        Duration.Inf,
      ) shouldEqual \/.right(())

      ordersClient.request.url().toString() shouldEqual "https://example.com/v1/orders"
      ordersClient.request.method() shouldEqual "POST"
      val requestBody = new Buffer()
      ordersClient.request.body().writeTo(requestBody)
      Json.parse(requestBody.readUtf8()) shouldEqual Json.toJson(
        ContributionAmountOrderRequest.forSubscription(
          accountId,
          subscriptionNumber,
          ratePlanId,
          chargeNumber,
          15.0,
          reason,
          orderDate,
          applyFromDate,
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
