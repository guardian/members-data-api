package acceptance

import acceptance.data.stripe.{TestCustomersPaymentMethods, TestDynamoSupporterRatePlanItem, TestStripeSubscription}
import acceptance.data.{
  IdentityResponse,
  TestAccountSummary,
  TestCatalog,
  TestContact,
  TestPaidCharge,
  TestPaidSubscriptionPlan,
  TestPaymentSummary,
  TestQueriesAccount,
  TestSubscription,
}
import com.gu.i18n.Currency
import com.gu.memsub.Product.Contribution
import com.gu.memsub.Subscription.Name
import com.gu.memsub.subsv2.{CovariantNonEmptyList, SubscriptionPlan}
import com.gu.memsub.{Product, Subscription}
import com.gu.monitoring.SafeLogger.LogPrefix
import kong.unirest.Unirest
import org.joda.time.format.DateTimeFormat
import org.joda.time.{LocalDate, LocalTime}
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchersSugar.eqTo
import org.mockserver.model.Cookie
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import play.api.ApplicationLoader.Context
import play.api.libs.json.Json.parse
import play.api.libs.json.{JsArray, Json}
import scalaz.\/
import services.mail.{EmailData, SendEmail}
import services.salesforce.ContactRepository
import services.stripe.BasicStripeService
import services.subscription.SubscriptionService
import services.zuora.rest.ZuoraRestService
import services.zuora.rest.ZuoraRestService.GiftSubscriptionsFromIdentityIdRecord
import services.zuora.soap.ZuoraSoapService
import services.{
  CatalogService,
  ContributionsStoreDatabaseService,
  HealthCheckableService,
  SupporterProductDataService,
  SupporterRatePlanToAttributesMapper,
}
import testdata.TestLogPrefix.testLogPrefix
import utils.SimpleEitherT
import wiring.MyComponents

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AccountControllerAcceptanceTest extends AcceptanceTest {
  var contactRepositoryMock: ContactRepository = _
  var subscriptionServiceMock: SubscriptionService = _
  var zuoraRestServiceMock: ZuoraRestService = _
  var catalogServiceMock: CatalogService = _
  var zuoraSoapServiceMock: ZuoraSoapService with HealthCheckableService = _
  var supporterProductDataServiceMock: SupporterProductDataService = _
  var databaseServiceMock: ContributionsStoreDatabaseService = _
  var patronsStripeServiceMock: BasicStripeService = _
  var sendEmailMock: SendEmail = _

  override protected def before: Unit = {
    contactRepositoryMock = mock[ContactRepository]
    subscriptionServiceMock = mock[SubscriptionService]
    zuoraRestServiceMock = mock[ZuoraRestService]
    catalogServiceMock = mock[CatalogService]
    zuoraSoapServiceMock = mock[ZuoraSoapService with HealthCheckableService]
    supporterProductDataServiceMock = mock[SupporterProductDataService]
    databaseServiceMock = mock[ContributionsStoreDatabaseService]
    patronsStripeServiceMock = mock[BasicStripeService]
    sendEmailMock = mock[SendEmail]
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
      override lazy val patronsStripeServiceOverride = Some(patronsStripeServiceMock)
      override lazy val sendEmail = sendEmailMock
    }
  }

  "AccountController" should {
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
            .withBody(
              IdentityResponse(
                userId = 200067388,
                firstName = "Frank",
                lastName = "Poole",
                email = "frank.poole@amail.com",
              ),
            ),
        )

      val contact = TestContact(identityId = "200067388")

      contactRepositoryMock.get("200067388")(any) returns Future(\/.right(Some(contact)))

      val giftSubscription = GiftSubscriptionsFromIdentityIdRecord(
        Id = "giftSubscriptionId",
        Name = "giftSubscriptionName",
        TermEndDate = LocalDate.now().plusYears(1),
      )

      zuoraRestServiceMock.getGiftSubscriptionRecordsFromIdentityId("200067388")(any) returns Future(
        \/.right(
          List(giftSubscription),
        ),
      )

      val nonGiftSubscription = TestSubscription()
      val nonGiftSubscriptionAccountId = nonGiftSubscription.accountId

      subscriptionServiceMock.current[SubscriptionPlan.AnyPlan](contact)(any, any) returns
        Future(List(nonGiftSubscription))

      val giftSubscriptionFromSubscriptionService = TestSubscription(
        id = Subscription.Id(giftSubscription.Id),
        name = Subscription.Name(giftSubscription.Name),
        plans = CovariantNonEmptyList(TestPaidSubscriptionPlan(product = Product.SupporterPlus), Nil),
      )
      val giftSubscriptionAccountId = giftSubscriptionFromSubscriptionService.accountId

      subscriptionServiceMock.get[SubscriptionPlan.AnyPlan](Subscription.Name(giftSubscription.Name), false)(any, any) returns Future.successful(
        Some(giftSubscriptionFromSubscriptionService),
      )

      catalogServiceMock.unsafeCatalog returns TestCatalog()

      zuoraRestServiceMock.getAccount(giftSubscriptionAccountId)(any) returns Future(\/.right(TestAccountSummary(id = giftSubscriptionAccountId)))
      zuoraRestServiceMock.getAccount(nonGiftSubscriptionAccountId)(any) returns Future(
        \/.right(TestAccountSummary(id = nonGiftSubscriptionAccountId)),
      )

      zuoraRestServiceMock.getCancellationEffectiveDate(giftSubscriptionFromSubscriptionService.name)(any) returns Future(\/.right(None))
      zuoraRestServiceMock.getCancellationEffectiveDate(nonGiftSubscription.name)(any) returns Future(\/.right(None))

      zuoraSoapServiceMock.getPaymentSummary(nonGiftSubscription.name, Currency.GBP)(any) returns Future(TestPaymentSummary())
      zuoraSoapServiceMock.getAccount(nonGiftSubscriptionAccountId)(any) returns Future(TestQueriesAccount())

      val patronSubscription = TestDynamoSupporterRatePlanItem(
        identityId = "200067388",
        productRatePlanId = SupporterRatePlanToAttributesMapper.guardianPatronProductRatePlanId,
      )
      supporterProductDataServiceMock.getSupporterRatePlanItems(eqTo("200067388"))(any) returns
        SimpleEitherT.right(List(patronSubscription))

      val stripeSubscription = TestStripeSubscription(id = patronSubscription.subscriptionName)

      patronsStripeServiceMock.fetchSubscription(patronSubscription.subscriptionName)(any) returns
        Future.successful(stripeSubscription)

      patronsStripeServiceMock.fetchPaymentMethod(stripeSubscription.customer.id)(any) returns
        Future.successful(TestCustomersPaymentMethods())

      val url = endpointUrl("/user-attributes/me/mma")

      val httpResponse = Unirest
        .get(url)
        .header(
          "Cookie",
          "consentUUID=cc457984-4282-49ca-9831-0649017aa0c9_13; _ga=GA1.2.1716429135.1668613133; GU_U=WyIyMDAwNjczODgiLCIiLCJ1c2VyIiwiIiwxNjc2NDU3MTE5ODk3LDEsMTY2ODYxMzI0ODAwMCx0cnVlXQ.MCwCFHUQjxr9nm5gk15jmWID6lYYVE5NAhR11ko5vRIjNwqGprB8gCzIEPz4Rg; SC_GU_LA=WyJMQSIsIjIwMDA2NzM4OCIsMTY2ODY4MTExOTg5N10.MCwCFG4nWLgEx96EJjNxwtqKbQBNBaXDAhRYtlpkPp8s_Ysl70rkySriLUKZaw; SC_GU_U=WyIyMDAwNjczODgiLDE2NzY0NTcxMTk4OTcsImI3NjE1ODMyYmE5OTQ0NzM4NTA5NTU2OTZiMjM1Yjg5IiwiIiwwXQ.MC0CFFJXLff5geHhf2EY_j_BQizPkUcnAhUAmoipMhDFsFmXuHY-a_ZXVJYPUHI",
        )
        .asString

      httpResponse.getStatus shouldEqual 200

      contactRepositoryMock.get("200067388")(any) was called
      supporterProductDataServiceMock.getSupporterRatePlanItems("200067388")(any[LogPrefix]) was called
      subscriptionServiceMock.current[SubscriptionPlan.AnyPlan](contact)(any, any) was called
      zuoraRestServiceMock.getGiftSubscriptionRecordsFromIdentityId("200067388")(any) was called
      subscriptionServiceMock.get[SubscriptionPlan.AnyPlan](Subscription.Name(giftSubscription.Name), isActiveToday = false)(any, any) was called
      catalogServiceMock.unsafeCatalog was called

      zuoraRestServiceMock.getAccount(giftSubscriptionAccountId)(any) was called
      zuoraRestServiceMock.getAccount(nonGiftSubscriptionAccountId)(any) was called

      zuoraRestServiceMock.getCancellationEffectiveDate(giftSubscriptionFromSubscriptionService.name)(any) was called
      zuoraRestServiceMock.getCancellationEffectiveDate(nonGiftSubscription.name)(any) was called

      zuoraSoapServiceMock.getAccount(nonGiftSubscriptionAccountId)(any) was called
      zuoraSoapServiceMock.getPaymentSummary(nonGiftSubscription.name, Currency.GBP)(any) was called

      supporterProductDataServiceMock wasNever calledAgain
      contactRepositoryMock wasNever calledAgain
      subscriptionServiceMock wasNever calledAgain
      zuoraRestServiceMock wasNever calledAgain
      catalogServiceMock wasNever calledAgain
      zuoraSoapServiceMock wasNever calledAgain
      databaseServiceMock wasNever called

      val body = httpResponse.getBody
      val json = Json.parse(body)

      identityMockClientAndServer.verify(redirectAdviceRequest)
      identityMockClientAndServer.verify(identityRequest)

      (json \ "user" \ "firstName").as[String] shouldEqual "Frank"
      (json \ "user" \ "lastName").as[String] shouldEqual "Poole"
      (json \ "user" \ "email").as[String] shouldEqual "frank.poole@amail.com"

      val productsArray = (json \ "products").as[JsArray].value
      productsArray.size shouldEqual 3

      val membershipProduct = productsArray.find(json => (json \ "mmaCategory").as[String] == "membership").get
      val supporterPlusProduct = productsArray.find(json => (json \ "mmaCategory").as[String] == "recurringSupport").get
      val patronProduct = productsArray.find(json => (json \ "mmaCategory").as[String] == "subscriptions").get

      (supporterPlusProduct \ "tier").as[String] shouldEqual giftSubscriptionFromSubscriptionService.plans.head.productName
      (supporterPlusProduct \ "isPaidTier").as[Boolean] shouldEqual false
      (supporterPlusProduct \ "subscription" \ "contactId").as[String] shouldEqual contact.salesforceContactId
      (supporterPlusProduct \ "subscription" \ "subscriptionId").as[String] shouldEqual giftSubscriptionFromSubscriptionService.name.get
      (supporterPlusProduct \ "subscription" \ "accountId").as[String] shouldEqual giftSubscriptionFromSubscriptionService.accountId.get
      (supporterPlusProduct \ "subscription" \ "plan" \ "name").as[String] shouldEqual giftSubscriptionFromSubscriptionService.plan.productName

      (membershipProduct \ "tier").as[String] shouldEqual nonGiftSubscription.plans.head.productName
      (membershipProduct \ "isPaidTier").as[Boolean] shouldEqual true
      (membershipProduct \ "subscription" \ "contactId").as[String] shouldEqual contact.salesforceContactId
      (membershipProduct \ "subscription" \ "subscriptionId").as[String] shouldEqual nonGiftSubscription.name.get
      (membershipProduct \ "subscription" \ "accountId").as[String] shouldEqual nonGiftSubscription.accountId.get
      (membershipProduct \ "subscription" \ "plan" \ "name").as[String] shouldEqual nonGiftSubscription.plan.productName

      (patronProduct \ "tier").as[String] shouldEqual "guardianpatron"
      (patronProduct \ "isPaidTier").as[Boolean] shouldEqual true
      (patronProduct \ "subscription" \ "contactId").as[String] shouldEqual "Guardian Patrons don't have a Salesforce contactId"
      (patronProduct \ "subscription" \ "subscriptionId").as[String] shouldEqual stripeSubscription.id
      (patronProduct \ "subscription" \ "accountId").as[String] shouldEqual stripeSubscription.customer.id
      (patronProduct \ "subscription" \ "plan" \ "name").as[String] shouldEqual "guardianpatron"
    }

    "update payment amount and send an email" in {
      val subscriptionId = "A-S00474148"

      val identityRequest = request()
        .withMethod("GET")
        .withPath("/user/me")
        .withHeader("X-GU-ID-Client-Access-Token", s"Bearer $identityApiToken")
        .withHeader(
          "X-GU-ID-FOWARDED-SC-GU-U",
          "WyIyMDAwNjQ2MTEiLDE2ODY3Nzg3NDQxMTUsIjY5MGVmNmYzMTllYTQwODI4OTBjMGExYTNkOWM5ZTFmIiwiIiwwXQ.MC0CFBUObHNIHJMVasjnW7HHRmeni8GdAhUAkJxvh4IR7UbMc5rL-QWk9J9trTE",
        )

      identityMockClientAndServer
        .when(
          identityRequest,
        )
        .respond(
          response()
            .withBody(
              IdentityResponse(
                userId = 200067388,
                firstName = "Frank",
                lastName = "Poole",
                email = "frank.poole@amail.com",
              ),
            ),
        )

      val contact = TestContact(
        identityId = "200067388",
        firstName = Some("Frank"),
        lastName = "Poole",
      )
      val emailData = EmailData(
        "frank.poole@amail.com",
        contact.salesforceContactId,
        "payment-amount-changed-email",
        Map(
          "first_name" -> "Frank",
          "last_name" -> "Poole",
          "new_amount" -> "12.00",
          "currency" -> "GBP",
          "frequency" -> "year",
          "next_payment_date" -> "11 March 2023",
        ),
      )

      contactRepositoryMock.get("200067388")(any) returns Future(\/.right(Some(contact)))

      val charge = TestPaidCharge()
      val chargedThroughDate = new LocalDate(2023, 3, 11)
      val plan = TestPaidSubscriptionPlan(
        product = Contribution,
        charges = charge,
        chargedThrough = Some(chargedThroughDate),
      )
      val subscription = TestSubscription(
        name = Subscription.Name(subscriptionId),
        plans = CovariantNonEmptyList(plan, Nil),
      ).asInstanceOf[com.gu.memsub.subsv2.Subscription[SubscriptionPlan.Contributor]]

      subscriptionServiceMock.current[SubscriptionPlan.Contributor](contact)(any, any) returns Future(List(subscription))

      zuoraRestServiceMock.updateChargeAmount(
        subscription.name,
        charge.subRatePlanChargeId,
        subscription.plan.id,
        12.00d,
        any,
        chargedThroughDate,
      )(any, any) returns Future(\/.right(()))

      sendEmailMock.send(emailData)(any) returns Future.successful(())

      val httpResponse = Unirest
        .post(endpointUrl(s"/user-attributes/me/contribution-update-amount/$subscriptionId"))
        .header("Csrf-Token", "nocheck")
        .header(
          "Cookie",
          "gu_paying_member=false; gu_digital_subscriber=true; gu_hide_support_messaging=true; consentUUID=ee459f1e-5d69-4def-a53c-c4a7b4b826f9_13; _ga=GA1.2.1494602535.1668613308; _gcl_au=1.1.1865802744.1673259395; QuantumMetricUserID=7a6ee3603e3f50079f57a932c7016208; gu_user_features_expiry=1676116644464; gu_recurring_contributor=true; GU_mvt_id=414642; GU_country=GB; GU_CO_COMPLETE={\"userType\":\"guest\",\"product\":\"SupporterPlus\"}; gu.contributions.contrib-timestamp=1678788098733; GU_geo_country=GB; _gid=GA1.2.1515141716.1678982877; GU_U=WyIyMDAwNjQ2MTEiLCIiLCJ1c2VyIiwiIiwxNjg2Nzc4NzQ0MTE1LDAsMTY2NzQwNjI0MTAwMCx0cnVlXQ.MC0CFGunCn-eCA9-AaJSyU1NuDQEHLK5AhUAlXRSJU9xBkZS5IcD4EPutZGjk4g; SC_GU_LA=WyJMQSIsIjIwMDA2NDYxMSIsMTY3OTAwMjc0NDExNV0.MC4CFQCI1EHaTXvNALwrnmCP6MlsgaB65QIVAIZb-ZFs38gpRYy1m6AOU65neA11; SC_GU_U=WyIyMDAwNjQ2MTEiLDE2ODY3Nzg3NDQxMTUsIjY5MGVmNmYzMTllYTQwODI4OTBjMGExYTNkOWM5ZTFmIiwiIiwwXQ.MC0CFBUObHNIHJMVasjnW7HHRmeni8GdAhUAkJxvh4IR7UbMc5rL-QWk9J9trTE; GU_AF1=1694900345726",
        )
        .field("newPaymentAmount", "12.00")
        .asString

      httpResponse.getStatus shouldEqual 200

      contactRepositoryMock.get("200067388")(any) was called

      identityMockClientAndServer.verify(identityRequest)
      subscriptionServiceMock.current[SubscriptionPlan.Contributor](contact)(any, any) was called
      zuoraRestServiceMock.updateChargeAmount(
        subscription.name,
        charge.subRatePlanChargeId,
        subscription.plan.id,
        12.00d,
        any,
        chargedThroughDate,
      )(any, any) was called

      sendEmailMock.send(emailData)(any) was called

      supporterProductDataServiceMock wasNever called
      contactRepositoryMock wasNever calledAgain
      subscriptionServiceMock wasNever calledAgain
      zuoraRestServiceMock wasNever calledAgain
      catalogServiceMock wasNever called
      zuoraSoapServiceMock wasNever called
      databaseServiceMock wasNever called
      sendEmailMock wasNever calledAgain

      1 shouldEqual 1
    }

    "cancel subscription and send an email" in {
      val subscriptionId = "A-S00474148"

      val identityRequest = request()
        .withMethod("GET")
        .withPath("/user/me")
        .withHeader("X-GU-ID-Client-Access-Token", s"Bearer $identityApiToken")
        .withHeader(
          "X-GU-ID-FOWARDED-SC-GU-U",
          "WyIyMDAwNjQ2MTEiLDE2ODY3Nzg3NDQxMTUsIjY5MGVmNmYzMTllYTQwODI4OTBjMGExYTNkOWM5ZTFmIiwiIiwwXQ.MC0CFBUObHNIHJMVasjnW7HHRmeni8GdAhUAkJxvh4IR7UbMc5rL-QWk9J9trTE",
        )

      identityMockClientAndServer
        .when(
          identityRequest,
        )
        .respond(
          response()
            .withBody(
              IdentityResponse(
                userId = 200067388,
                firstName = "Frank",
                lastName = "Poole",
                email = "frank.poole@amail.com",
              ),
            ),
        )

      val contact = TestContact(
        identityId = "200067388",
        firstName = Some("Frank"),
        lastName = "Poole",
      )
      val emailData = EmailData(
        "frank.poole@amail.com",
        contact.salesforceContactId,
        "subscription-cancelled-email",
        Map(
          "first_name" -> "Frank",
          "last_name" -> "Poole",
          "product_type" -> "Digital Pack",
          "cancellation_effective_date" -> "5 April 2024",
        ),
      )

      contactRepositoryMock.get("200067388")(any) returns Future(\/.right(Some(contact)))

      val subscription = TestSubscription(
        name = Subscription.Name(subscriptionId),
        plans = CovariantNonEmptyList(TestPaidSubscriptionPlan(productType = "Digital Pack"), Nil),
        termEndDate = new LocalDate(2024, 4, 12),
      )

      subscriptionServiceMock.current[SubscriptionPlan.AnyPlan](contact)(any, any) returns Future(List(subscription))

      val cancellationEffectiveDate = new LocalDate(2024, 4, 5)
      subscriptionServiceMock
        .decideCancellationEffectiveDate[SubscriptionPlan.AnyPlan](Name(subscriptionId), any, any)(any, any) returns SimpleEitherT
        .right(Some(cancellationEffectiveDate))

      subscriptionServiceMock
        .subscriptionsForAccountId[SubscriptionPlan.AnyPlan](subscription.accountId)(any, any) shouldReturn SimpleEitherT
        .right(List(subscription))
        .run

      zuoraRestServiceMock.disableAutoPay(subscription.accountId)(any) returns unit()

      zuoraRestServiceMock.updateCancellationReason(Name(subscriptionId), "My reason")(any) returns unit()

      zuoraRestServiceMock.cancelSubscription(Name(subscriptionId), subscription.termEndDate, Some(cancellationEffectiveDate))(
        any,
        any,
      ) returns unit()

      sendEmailMock.send(emailData)(any) returns Future.successful(())

      val httpResponse = Unirest
        .post(endpointUrl(s"/user-attributes/me/cancel/$subscriptionId"))
        .header("Csrf-Token", "nocheck")
        .header(
          "Cookie",
          "gu_paying_member=false; gu_digital_subscriber=true; gu_hide_support_messaging=true; consentUUID=ee459f1e-5d69-4def-a53c-c4a7b4b826f9_13; _ga=GA1.2.1494602535.1668613308; _gcl_au=1.1.1865802744.1673259395; QuantumMetricUserID=7a6ee3603e3f50079f57a932c7016208; gu_user_features_expiry=1676116644464; gu_recurring_contributor=true; GU_mvt_id=414642; GU_country=GB; GU_CO_COMPLETE={\"userType\":\"guest\",\"product\":\"SupporterPlus\"}; gu.contributions.contrib-timestamp=1678788098733; GU_geo_country=GB; _gid=GA1.2.1515141716.1678982877; GU_U=WyIyMDAwNjQ2MTEiLCIiLCJ1c2VyIiwiIiwxNjg2Nzc4NzQ0MTE1LDAsMTY2NzQwNjI0MTAwMCx0cnVlXQ.MC0CFGunCn-eCA9-AaJSyU1NuDQEHLK5AhUAlXRSJU9xBkZS5IcD4EPutZGjk4g; SC_GU_LA=WyJMQSIsIjIwMDA2NDYxMSIsMTY3OTAwMjc0NDExNV0.MC4CFQCI1EHaTXvNALwrnmCP6MlsgaB65QIVAIZb-ZFs38gpRYy1m6AOU65neA11; SC_GU_U=WyIyMDAwNjQ2MTEiLDE2ODY3Nzg3NDQxMTUsIjY5MGVmNmYzMTllYTQwODI4OTBjMGExYTNkOWM5ZTFmIiwiIiwwXQ.MC0CFBUObHNIHJMVasjnW7HHRmeni8GdAhUAkJxvh4IR7UbMc5rL-QWk9J9trTE; GU_AF1=1694900345726",
        )
        .field("reason", "My reason")
        .asString

      httpResponse.getStatus shouldEqual 200

      contactRepositoryMock.get("200067388")(any) was called

      identityMockClientAndServer.verify(identityRequest)
      subscriptionServiceMock.current[SubscriptionPlan.Contributor](contact)(any, any) was called

      subscriptionServiceMock.decideCancellationEffectiveDate[SubscriptionPlan.AnyPlan](Name(subscriptionId), any, any)(any, any) was called
      subscriptionServiceMock.subscriptionsForAccountId[SubscriptionPlan.AnyPlan](subscription.accountId)(any, any) was called
      zuoraRestServiceMock.disableAutoPay(subscription.accountId)(any) was called
      zuoraRestServiceMock.updateCancellationReason(Name(subscriptionId), "My reason")(any) was called
      zuoraRestServiceMock.cancelSubscription(Name(subscriptionId), subscription.termEndDate, Some(cancellationEffectiveDate))(any, any) was called

      sendEmailMock.send(emailData)(any) was called

      supporterProductDataServiceMock wasNever called
      contactRepositoryMock wasNever calledAgain
      subscriptionServiceMock wasNever calledAgain
      zuoraRestServiceMock wasNever calledAgain
      catalogServiceMock wasNever called
      zuoraSoapServiceMock wasNever called
      databaseServiceMock wasNever called
      sendEmailMock wasNever calledAgain

      val responseBody = httpResponse.getBody
      val responseJson = Json.parse(responseBody)
      val cancellationDateFormatted = DateTimeFormat.forPattern("yyyy-MM-dd").print(cancellationEffectiveDate)
      responseJson shouldEqual Json.parse(s"""
          |{"cancellationEffectiveDate":"$cancellationDateFormatted"}
          |""".stripMargin)
    }
  }

  def unit() = Future.successful(\/.right[String, Unit](()))
}
