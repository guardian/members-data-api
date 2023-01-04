package acceptance

import acceptance.data.Randoms.randomId
import acceptance.data.{
  IdentityResponse,
  TestAccountSummary,
  TestCatalog,
  TestContact,
  TestPaidSubscriptionPlan,
  TestPaymentSummary,
  TestQueriesAccount,
  TestSubscription,
}
import cats.data.EitherT
import com.gu.i18n.Currency
import com.gu.memsub.subsv2.{CovariantNonEmptyList, SubscriptionPlan}
import com.gu.memsub.{Product, Subscription}
import com.gu.memsub.subsv2.services.{CatalogService, SubscriptionService}
import com.gu.salesforce.SimpleContactRepository
import com.gu.zuora.ZuoraSoapService
import com.gu.zuora.rest.ZuoraRestService
import com.gu.zuora.rest.ZuoraRestService.GiftSubscriptionsFromIdentityIdRecord
import kong.unirest.Unirest
import models.DynamoSupporterRatePlanItem
import org.joda.time.LocalDate
import org.mockito.ArgumentMatchers.any
import org.mockserver.model.Cookie
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import play.api.ApplicationLoader.Context
import play.api.libs.json.{JsArray, Json}
import scalaz.\/
import services.{ContributionsStoreDatabaseService, HealthCheckableService, SupporterProductDataService}
import wiring.MyComponents

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class AccountControllerAcceptanceTest extends AcceptanceTest {
  var contactRepositoryMock: SimpleContactRepository = _
  var subscriptionServiceMock: SubscriptionService[Future] = _
  var zuoraRestServiceMock: ZuoraRestService[Future] = _
  var catalogServiceMock: CatalogService[Future] = _
  var zuoraSoapServiceMock: ZuoraSoapService with HealthCheckableService = _
  var supporterProductDataServiceMock: SupporterProductDataService = _
  var databaseServiceMock: ContributionsStoreDatabaseService = _

  override protected def before: Unit = {
    contactRepositoryMock = mock[SimpleContactRepository]
    subscriptionServiceMock = mock[SubscriptionService[Future]]
    zuoraRestServiceMock = mock[ZuoraRestService[Future]]
    catalogServiceMock = mock[CatalogService[Future]]
    zuoraSoapServiceMock = mock[ZuoraSoapService with HealthCheckableService]
    supporterProductDataServiceMock = mock[SupporterProductDataService]
    databaseServiceMock = mock[ContributionsStoreDatabaseService]
    super.before
  }

  override def createMyComponents(context: Context): MyComponents = {
    new MyComponents(context) {
      override lazy val supporterProductDataServiceOverride = Some(supporterProductDataServiceMock)
      override lazy val contactRepositoryOverride = Some(contactRepositoryMock)
      override lazy val subscriptionServiceOverride = Some(subscriptionServiceMock)
      override lazy val zuoraRestServiceOverride = Some(zuoraRestServiceMock)
      override lazy val catalogServiceOverride = Some(catalogServiceMock)
      override lazy val zuoraSoapServiceOverride = Some(zuoraSoapServiceMock)
      override lazy val dbService = databaseServiceMock
    }
  }

  "AttributeController" should {
    "serve current user's products and data" in {
      val cookies = Map(
        "GU_U" -> "WyIyMDAwNjczODgiLCIiLCJ1c2VyIiwiIiwxNjc2NDU3MTE5ODk3LDEsMTY2ODYxMzI0ODAwMCx0cnVlXQ.MCwCFHUQjxr9nm5gk15jmWID6lYYVE5NAhR11ko5vRIjNwqGprB8gCzIEPz4Rg",
        "SC_GU_LA" -> "WyJMQSIsIjIwMDA2NzM4OCIsMTY2ODY4MTExOTg5N10.MCwCFG4nWLgEx96EJjNxwtqKbQBNBaXDAhRYtlpkPp8s_Ysl70rkySriLUKZaw",
        "SC_GU_U" -> "WyIyMDAwNjczODgiLDE2NzY0NTcxMTk4OTcsImI3NjE1ODMyYmE5OTQ0NzM4NTA5NTU2OTZiMjM1Yjg5IiwiIiwwXQ.MC0CFFJXLff5geHhf2EY_j_BQizPkUcnAhUAmoipMhDFsFmXuHY-a_ZXVJYPUHI",
        "_ga" -> "GA1.2.1716429135.1668613133",
        "consentUUID" -> "cc457984-4282-49ca-9831-0649017aa0c9_13",
      ).toList.map { case (key, value) => new Cookie(key, value) }

      val redirectAdviceRequest = request()
        .withMethod("GET")
        .withPath("/auth/redirect")
        .withHeader("X-GU-ID-Client-Access-Token", s"Bearer $identityApiToken")
        .withCookies(cookies: _*)

      val identityRequest = request()
        .withMethod("GET")
        .withPath("/user/me")
        .withHeader("X-GU-ID-Client-Access-Token", s"Bearer $identityApiToken")
        .withHeader(
          "X-GU-ID-FOWARDED-SC-GU-U",
          "WyIyMDAwNjczODgiLDE2NzY0NTcxMTk4OTcsImI3NjE1ODMyYmE5OTQ0NzM4NTA5NTU2OTZiMjM1Yjg5IiwiIiwwXQ.MC0CFFJXLff5geHhf2EY_j_BQizPkUcnAhUAmoipMhDFsFmXuHY-a_ZXVJYPUHI",
        )

      identityMockClientAndServer
        .when(redirectAdviceRequest)
        .respond(
          response(
            s"""{
               |"signInStatus": "signedInRecently",
               |"userId": "200067388"
               |}""".stripMargin,
          ),
        )

      identityMockClientAndServer
        .when(
          identityRequest,
        )
        .respond(
          response()
            .withBody(IdentityResponse(userId = 200067388)),
        )

      val contact = TestContact(identityId = "200067388")

      contactRepositoryMock.get("200067388") returns Future(\/.right(Some(contact)))

      val giftSubscription = GiftSubscriptionsFromIdentityIdRecord(
        randomId("giftSubscriptionName"),
        randomId("giftSubscriptionId"),
        LocalDate.now().plusYears(1),
      )
      zuoraRestServiceMock.getGiftSubscriptionRecordsFromIdentityId("200067388") returns Future(
        \/.right(
          List(giftSubscription),
        ),
      )

      val nonGiftSubscription = TestSubscription()
      val nonGiftSubscriptionAccountId = nonGiftSubscription.accountId
      subscriptionServiceMock.current[SubscriptionPlan.AnyPlan](contact)(any) returns
        Future(List(nonGiftSubscription))

      val giftSubscriptionFromSubscriptionService = TestSubscription(
        id = Subscription.Id(giftSubscription.Id),
        name = Subscription.Name(giftSubscription.Name),
        plans = CovariantNonEmptyList(TestPaidSubscriptionPlan(product = Product.SupporterPlus), Nil),
      )
      val giftSubscriptionAccountId = giftSubscriptionFromSubscriptionService.accountId

      subscriptionServiceMock.get[SubscriptionPlan.AnyPlan](Subscription.Name(giftSubscription.Name), false)(any) returns Future.successful(
        Some(giftSubscriptionFromSubscriptionService),
      )

      catalogServiceMock.unsafeCatalog returns TestCatalog()

      zuoraRestServiceMock.getAccount(giftSubscriptionAccountId) returns Future(\/.right(TestAccountSummary(id = giftSubscriptionAccountId)))
      zuoraRestServiceMock.getAccount(nonGiftSubscriptionAccountId) returns Future(\/.right(TestAccountSummary(id = nonGiftSubscriptionAccountId)))

      zuoraRestServiceMock.getCancellationEffectiveDate(giftSubscriptionFromSubscriptionService.name) returns Future(\/.right(None))
      zuoraRestServiceMock.getCancellationEffectiveDate(nonGiftSubscription.name) returns Future(\/.right(None))

      zuoraSoapServiceMock.getPaymentSummary(nonGiftSubscription.name, Currency.GBP) returns Future(TestPaymentSummary())
      zuoraSoapServiceMock.getAccount(nonGiftSubscriptionAccountId) returns Future(TestQueriesAccount())

      supporterProductDataServiceMock.getSupporterRatePlanItems("200067388") returns
        EitherT[Future, String, List[DynamoSupporterRatePlanItem]](Future(Right(Nil)))

      val httpResponse = Unirest
        .get(endpointUrl("/user-attributes/me/mma"))
        .header(
          "Cookie",
          "consentUUID=cc457984-4282-49ca-9831-0649017aa0c9_13; _ga=GA1.2.1716429135.1668613133; GU_U=WyIyMDAwNjczODgiLCIiLCJ1c2VyIiwiIiwxNjc2NDU3MTE5ODk3LDEsMTY2ODYxMzI0ODAwMCx0cnVlXQ.MCwCFHUQjxr9nm5gk15jmWID6lYYVE5NAhR11ko5vRIjNwqGprB8gCzIEPz4Rg; SC_GU_LA=WyJMQSIsIjIwMDA2NzM4OCIsMTY2ODY4MTExOTg5N10.MCwCFG4nWLgEx96EJjNxwtqKbQBNBaXDAhRYtlpkPp8s_Ysl70rkySriLUKZaw; SC_GU_U=WyIyMDAwNjczODgiLDE2NzY0NTcxMTk4OTcsImI3NjE1ODMyYmE5OTQ0NzM4NTA5NTU2OTZiMjM1Yjg5IiwiIiwwXQ.MC0CFFJXLff5geHhf2EY_j_BQizPkUcnAhUAmoipMhDFsFmXuHY-a_ZXVJYPUHI",
        )
        .asString

      contactRepositoryMock.get("200067388") was called
      subscriptionServiceMock.current[SubscriptionPlan.AnyPlan](contact)(any) was called
      zuoraRestServiceMock.getGiftSubscriptionRecordsFromIdentityId("200067388") was called
      subscriptionServiceMock.get[SubscriptionPlan.AnyPlan](Subscription.Name(giftSubscription.Name), isActiveToday = false)(any) was called
      catalogServiceMock.unsafeCatalog was called

      zuoraRestServiceMock.getAccount(giftSubscriptionAccountId) was called
      zuoraRestServiceMock.getAccount(nonGiftSubscriptionAccountId) was called

      zuoraRestServiceMock.getCancellationEffectiveDate(giftSubscriptionFromSubscriptionService.name) was called
      zuoraRestServiceMock.getCancellationEffectiveDate(nonGiftSubscription.name) was called

      zuoraSoapServiceMock.getPaymentSummary(nonGiftSubscription.name, Currency.GBP) was called
      zuoraSoapServiceMock.getAccount(nonGiftSubscriptionAccountId) was called

      contactRepositoryMock wasNever calledAgain
      subscriptionServiceMock wasNever calledAgain
      zuoraRestServiceMock wasNever calledAgain
      catalogServiceMock wasNever calledAgain
      zuoraRestServiceMock wasNever calledAgain
      zuoraSoapServiceMock wasNever calledAgain
      databaseServiceMock wasNever called

      httpResponse.getStatus shouldEqual 200

      val body = httpResponse.getBody
      val json = Json.parse(body)

      identityMockClientAndServer.verify(redirectAdviceRequest)
      identityMockClientAndServer.verify(identityRequest)

      val productsArray = json.as[JsArray].value
      productsArray.size shouldEqual 2
      val membershipProduct = productsArray.find(json => (json \ "mmaCategory").as[String] == "membership").get
      val supporterPlusProduct = productsArray.find(json => (json \ "mmaCategory").as[String] == "recurringSupport").get

      (supporterPlusProduct \ "tier").as[String] shouldEqual giftSubscriptionFromSubscriptionService.plans.head.productName
      (supporterPlusProduct \ "subscription" \ "contactId").as[String] shouldEqual contact.salesforceContactId
      (supporterPlusProduct \ "subscription" \ "subscriptionId").as[String] shouldEqual giftSubscriptionFromSubscriptionService.name.get
      (supporterPlusProduct \ "subscription" \ "accountId").as[String] shouldEqual giftSubscriptionFromSubscriptionService.accountId.get
      (supporterPlusProduct \ "subscription" \ "plan" \ "name").as[String] shouldEqual giftSubscriptionFromSubscriptionService.plan.productName

      (membershipProduct \ "tier").as[String] shouldEqual nonGiftSubscription.plans.head.productName
      (membershipProduct \ "subscription" \ "contactId").as[String] shouldEqual contact.salesforceContactId
      (membershipProduct \ "subscription" \ "subscriptionId").as[String] shouldEqual nonGiftSubscription.name.get
      (membershipProduct \ "subscription" \ "accountId").as[String] shouldEqual nonGiftSubscription.accountId.get
      (membershipProduct \ "subscription" \ "plan" \ "name").as[String] shouldEqual nonGiftSubscription.plan.productName
    }
  }
}
