package memsub.subsv2.services

import com.gu.i18n.Currency._
import com.gu.i18n.{Country, Title}
import memsub.subsv2.Fixtures.productIds
import models.subscription.Benefit.Weekly
import models.subscription.Status
import models.subscription.Subscription.{AccountId, AccountNumber, ProductRatePlanChargeId, ProductRatePlanId}
import models.subscription.subsv2.CatalogZuoraPlan
import okhttp3._
import org.joda.time.{DateTime, LocalDate}
import org.specs2.mutable.Specification
import scalaz.Scalaz.futureInstance
import scalaz.{\/, \/-}
import services.zuora.ZuoraRestConfig
import services.zuora.rest.ZuoraRestService.{
  AccountSummary,
  BillToContact,
  DefaultPaymentMethod,
  Invoice,
  InvoiceId,
  PaidInvoice,
  Payment,
  PaymentMethodId,
  SalesforceContactId,
  SoldToContact,
}
import services.zuora.rest.{SimpleClient, SimpleClientZuoraRestService, ZuoraRestService}
import services.zuora.soap.{StripeAUMembershipGateway, StripeUKMembershipGateway}
import util.Await.waitFor
import util.{CreateNoopMetrics, Resource}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ZuoraRestServiceTest extends Specification {

  val cat = {
    val sixMonths = ProductRatePlanId("2c92c0f85a4b3a23015a5be3fc2271ad")
    val sixMonthly = ProductRatePlanId("2c92c0f95a4b48b8015a5be1205d042b")
    val oneYear = ProductRatePlanId("2c92c0f9585841e7015862c9128e153b")
    Map[ProductRatePlanId, CatalogZuoraPlan](
      sixMonths -> CatalogZuoraPlan(
        sixMonths,
        "foo",
        "",
        productIds.weeklyZoneB,
        None,
        List.empty,
        Map(ProductRatePlanChargeId("2c92c0f85a4b3a23015a5be3fc4471af") -> Weekly),
        Status.current,
        None,
      ),
      sixMonthly -> CatalogZuoraPlan(
        sixMonthly,
        "foo",
        "",
        productIds.weeklyZoneB,
        None,
        List.empty,
        Map(ProductRatePlanChargeId("2c92c0f95a4b48b8015a5be1206d042d") -> Weekly),
        Status.current,
        None,
      ),
      oneYear -> CatalogZuoraPlan(
        oneYear,
        "foo",
        "",
        productIds.weeklyZoneB,
        None,
        List.empty,
        Map(ProductRatePlanChargeId("2c92c0f958aa455e0158cf78302219f5") -> Weekly),
        Status.current,
        None,
      ),
    )
  }

  "subscription transform" should {
    "load a one year guardian weekly subscription" in {

      val invoiceDate1 = new DateTime().withYear(2017).withMonthOfYear(5).withDayOfMonth(5).withTimeAtStartOfDay()
      val invoiceDate2 = new DateTime().withYear(2017).withMonthOfYear(2).withDayOfMonth(26).withTimeAtStartOfDay()

      val client = ZuoraRestServiceTest.client("rest/accounts/Migrated.json")
      val zuoraRestService = new SimpleClientZuoraRestService(client)
      val result: String \/ ZuoraRestService.AccountSummary = waitFor(zuoraRestService.getAccount(AccountId("dummy")))
      val expected: String \/ ZuoraRestService.AccountSummary =
        \/-(
          AccountSummary(
            billToContact = BillToContact(country = Some(Country.Australia), email = Some("dummy@thegulocal.com")),
            currency = Some(AUD),
            id = AccountId("dummy"),
            accountNumber = AccountNumber("dummy"),
            identityId = Some("123"),
            soldToContact = SoldToContact(
              title = Some(Title.Mx),
              address1 = Some("1 Dummy Street"),
              address2 = None,
              city = Some("DummyCity"),
              country = Some(Country.Australia),
              firstName = Some("A.B."),
              lastName = "Dummy",
              email = Some("dummy@thegulocal.com"),
              postCode = Some("1234"),
              state = None,
            ),
            invoices = List(
              Invoice(
                id = InvoiceId("2c92a0ae5baeaa01015bd7a80a9864b6"),
                invoiceNumber = "INV01639634",
                invoiceDate = invoiceDate1,
                dueDate = invoiceDate1,
                amount = 70.2,
                balance = 0,
                status = "Posted",
              ),
              Invoice(
                id = InvoiceId("2c92a0a65a83f18f015aab998874599b"),
                invoiceNumber = "INV01315863",
                invoiceDate = invoiceDate2,
                dueDate = invoiceDate2,
                amount = 168.3,
                balance = 0,
                status = "Posted",
              ),
            ),
            payments = List(
              Payment(
                status = "Processed",
                paidInvoices = List(PaidInvoice("INV01639634", 70.2)),
              ),
              Payment(
                status = "Processed",
                paidInvoices = List(PaidInvoice("INV01315863", 153.0)),
              ),
            ),
            balance = 0,
            defaultPaymentMethod = Some(DefaultPaymentMethod(PaymentMethodId("dummyPayment"))),
            sfContactId = SalesforceContactId("sfContactId"),
          ),
        )
      result mustEqual expected
    }
  }

  "getAccounts" should {
    "parse valid json" in {

      val lastInvoiceDate = new DateTime().withYear(2018).withMonthOfYear(1).withDayOfMonth(26).withTimeAtStartOfDay()

      implicit val client = ZuoraRestServiceTest.client("rest/accounts/AccountQueryResponse.json")
      val zuoraRestService = new SimpleClientZuoraRestService(client)
      val result: String \/ ZuoraRestService.GetAccountsQueryResponse = waitFor(zuoraRestService.getAccounts("1234"))
      val expected: String \/ ZuoraRestService.GetAccountsQueryResponse =
        \/-(
          ZuoraRestService.GetAccountsQueryResponse(
            records = List(
              ZuoraRestService.AccountObject(AccountId("2c92c0f85cee08f3015cf32fa5df14a1"), 0, Some(GBP)),
              ZuoraRestService.AccountObject(
                AccountId("2c92c0f95cee23f3015cf37ef9f24b6a"),
                12.34,
                Some(USD),
                Some(PaymentMethodId("2c92a0fd58339435015844cd964c75d2")),
                Some(StripeAUMembershipGateway),
              ),
              ZuoraRestService.AccountObject(
                AccountId("2c92c0f8610ddce501613228973713a8"),
                56.78,
                Some(GBP),
                Some(PaymentMethodId("2c92c0f8610ddce501613228977313ac")),
                Some(StripeUKMembershipGateway),
                Some(lastInvoiceDate),
              ),
            ),
            size = 2,
          ),
        )
      result mustEqual expected
    }
  }

  "getGiftSubscriptionRecordsFromIdentityId" should {
    "parse valid json" in {

      val termEndDate = new LocalDate(2021, 10, 8)

      implicit val client = ZuoraRestServiceTest.client("rest/GiftSubscriptions.json")
      val zuoraRestService = new SimpleClientZuoraRestService(client)
      val result = waitFor(zuoraRestService.getGiftSubscriptionRecordsFromIdentityId("1234"))
      val expected =
        \/-(
          List(
            ZuoraRestService.GiftSubscriptionsFromIdentityIdRecord("subname", "2c92c0fa74e96420017501ba59171832", termEndDate),
            ZuoraRestService.GiftSubscriptionsFromIdentityIdRecord("subname", "2c92c0fa74e964300175029626ef1f63", termEndDate),
          ),
        )
      result mustEqual expected
    }
  }

  "getPaymentMethod" should {
    "parse valid json" in {
      implicit val client = ZuoraRestServiceTest.client("rest/paymentmethod/PaymentMethod.json")
      val zuoraRestService = new SimpleClientZuoraRestService(client)
      val result: String \/ ZuoraRestService.PaymentMethodResponse = waitFor(zuoraRestService.getPaymentMethod("1234"))

      // "2018-02-09T15:03:00.000+00:00"
      val expectedLastTransactionDate = new DateTime()
        .withYear(2018)
        .withMonthOfYear(2)
        .withDayOfMonth(9)
        .withHourOfDay(15)
        .withMinuteOfHour(3)
        .withSecondOfMinute(0)
        .withMillisOfSecond(0)
      val expected =
        \/.r[String](
          ZuoraRestService.PaymentMethodResponse(
            numConsecutiveFailures = 0,
            paymentMethodType = "CreditCardReferenceTransaction",
            lastTransactionDateTime = expectedLastTransactionDate,
          ),
        )
      result mustEqual expected
    }
  }
}

object ZuoraRestServiceTest {

  def client(path: String) = {
    val runner = (r: Request) =>
      Future.successful(
        new Response.Builder()
          .request(r)
          .message("test")
          .code(200)
          .body(ResponseBody.create(MediaType.parse("application/json"), Resource.getJson(path).toString))
          .protocol(Protocol.HTTP_1_1)
          .build(),
      )

    import io.lemonlabs.uri.dsl._
    val restConfig = ZuoraRestConfig("foo", "http://localhost", "joe", "public")
    new SimpleClient(restConfig, runner, CreateNoopMetrics)
  }

}
