package acceptance

import acceptance.data.Randoms.randomId
import acceptance.data._
import acceptance.data.stripe.{TestStripeCard, TestStripeCustomer}
import com.gu.i18n.Country
import com.gu.memsub.Subscription
import com.gu.memsub.subsv2.services.SubscriptionService.CatalogMap
import com.gu.memsub.subsv2.services.{CatalogService, SubscriptionService}
import com.gu.memsub.subsv2.{CovariantNonEmptyList, RatePlan}
import com.gu.zuora.ZuoraSoapService
import com.gu.zuora.api.{GoCardlessZuoraInstance, PaymentGateway}
import com.gu.zuora.soap.models.Commands.{BankTransfer, CreatePaymentMethod}
import com.gu.zuora.soap.models.Queries
import com.gu.zuora.soap.models.Results.UpdateResult
import kong.unirest.Unirest
import org.mockito.ArgumentMatchers.any
import org.mockserver.model.Cookie
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import play.api.ApplicationLoader.Context
import play.api.libs.json.Json
import scalaz.Id.Id
import scalaz.\/
import services.mail.{EmailData, SendEmail}
import services.salesforce.ContactRepository
import services.stripe.{BasicStripeService, ChooseStripe, StripePublicKey, StripeService}
import services.zuora.rest.ZuoraRestService
import services.{ContributionsStoreDatabaseService, HealthCheckableService, SupporterProductDataService}
import wiring.MyComponents

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PaymentUpdateControllerAcceptanceTest extends AcceptanceTest {
  var contactRepositoryMock: ContactRepository = _
  var subscriptionServiceMock: SubscriptionService[Future] = _
  var zuoraRestServiceMock: ZuoraRestService = _
  var catalogServiceMock: CatalogMap = _
  var zuoraSoapServiceMock: ZuoraSoapService with HealthCheckableService = _
  var supporterProductDataServiceMock: SupporterProductDataService = _
  var databaseServiceMock: ContributionsStoreDatabaseService = _
  var patronsStripeServiceMock: BasicStripeService = _
  var sendEmailMock: SendEmail = _
  var ukStripeServiceMock: StripeService = _
  val ukStripePublicKey: StripePublicKey = StripePublicKey("ukStripePublicKey")
  var chooseStripe: ChooseStripe = _

  override protected def before: Unit = {
    contactRepositoryMock = mock[ContactRepository]
    subscriptionServiceMock = mock[SubscriptionService[Future]]
    zuoraRestServiceMock = mock[ZuoraRestService]
    catalogServiceMock = TestCatalog()
    zuoraSoapServiceMock = mock[ZuoraSoapService with HealthCheckableService]
    supporterProductDataServiceMock = mock[SupporterProductDataService]
    databaseServiceMock = mock[ContributionsStoreDatabaseService]
    patronsStripeServiceMock = mock[BasicStripeService]
    sendEmailMock = mock[SendEmail]
    ukStripeServiceMock = mock[StripeService]
    chooseStripe = new ChooseStripe(
      Map(Country.UK -> ukStripePublicKey),
      ukStripePublicKey,
      Map(ukStripePublicKey -> ukStripeServiceMock),
    )
    super.before
  }

  override def createMyComponents(context: Context): MyComponents = {
    new MyComponents(context) {
      override lazy val supporterProductDataServiceOverride = Some(supporterProductDataServiceMock)
      override lazy val contactRepositoryOverride = Some(contactRepositoryMock)
      override lazy val subscriptionServiceOverride = Some(subscriptionServiceMock)
      override lazy val zuoraRestServiceOverride = Some(zuoraRestServiceMock)
      override lazy val catalogServiceOverride = Some(Future.successful(catalogServiceMock))
      override lazy val zuoraSoapServiceOverride = Some(zuoraSoapServiceMock)
      override lazy val dbService = databaseServiceMock
      override lazy val patronsStripeServiceOverride = Some(patronsStripeServiceMock)
      override lazy val sendEmail = sendEmailMock
      override lazy val chooseStripeOverride: Option[ChooseStripe] = Some(chooseStripe)
    }
  }

  "PaymentUpdateController" should {
    "update payment method to direct debit and send an email" in {
      val subscriptionId = "A-S00474148"

      val cookies = Map(
        "GU_U" -> "WyIyMDAwNjQ2MTEiLCIiLCJ1c2VyIiwiIiwxNjg2Nzc4NzQ0MTE1LDAsMTY2NzQwNjI0MTAwMCx0cnVlXQ.MC0CFGunCn-eCA9-AaJSyU1NuDQEHLK5AhUAlXRSJU9xBkZS5IcD4EPutZGjk4g",
        "SC_GU_LA" -> "WyJMQSIsIjIwMDA2NDYxMSIsMTY3OTAwMjc0NDExNV0.MC4CFQCI1EHaTXvNALwrnmCP6MlsgaB65QIVAIZb-ZFs38gpRYy1m6AOU65neA11",
        "SC_GU_U" -> "WyIyMDAwNjQ2MTEiLDE2ODY3Nzg3NDQxMTUsIjY5MGVmNmYzMTllYTQwODI4OTBjMGExYTNkOWM5ZTFmIiwiIiwwXQ.MC0CFBUObHNIHJMVasjnW7HHRmeni8GdAhUAkJxvh4IR7UbMc5rL-QWk9J9trTE",
        "consentUUID" -> "ee459f1e-5d69-4def-a53c-c4a7b4b826f9_13",
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
          "WyIyMDAwNjQ2MTEiLDE2ODY3Nzg3NDQxMTUsIjY5MGVmNmYzMTllYTQwODI4OTBjMGExYTNkOWM5ZTFmIiwiIiwwXQ.MC0CFBUObHNIHJMVasjnW7HHRmeni8GdAhUAkJxvh4IR7UbMc5rL-QWk9J9trTE",
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

      val contact = TestContact(
        identityId = "200067388",
        firstName = Some("Frank"),
        lastName = "Poole",
      )
      val emailData = EmailData(
        "frank.poole@amail.com",
        contact.salesforceContactId,
        "payment-method-changed-email",
        Map(
          "first_name" -> "Frank",
          "last_name" -> "Poole",
          "payment_method" -> "direct_debit",
          "product_type" -> "Newspaper - Home Delivery",
        ),
      )

      contactRepositoryMock.get("200067388")(any) returns Future(\/.right(Some(contact)))

      val subscription = TestSubscription(
        name = Subscription.Name(subscriptionId),
        plans = CovariantNonEmptyList(TestPaidSubscriptionPlan(productType = "Newspaper - Home Delivery"), Nil),
      )

      subscriptionServiceMock.current(contact)(any) returns Future(List(subscription))

      val account = TestQueriesAccount()
      val paymentMethodId = randomId("paymentMethod")
      val accountWithUpdatedPaymentMethodId = account.copy(defaultPaymentMethodId = Some(paymentMethodId))
      zuoraSoapServiceMock.getAccount(subscription.accountId)(any) returns Future(account) andThen Future(accountWithUpdatedPaymentMethodId)

      val queriesContact = TestQueriesContact()
      zuoraSoapServiceMock.getContact(account.billToId)(any) returns Future(queriesContact)

      val bankTransferPaymentMethod = BankTransfer(
        accountHolderName = "Frank Poole",
        accountNumber = "4444 4444 4444 4444",
        sortCode = "000000",
        firstName = queriesContact.firstName,
        lastName = queriesContact.lastName,
        countryCode = "GB",
      )
      val createPaymentMethod = CreatePaymentMethod(
        accountId = subscription.accountId,
        paymentMethod = bankTransferPaymentMethod,
        paymentGateway = GoCardlessZuoraInstance,
        billtoContact = queriesContact,
        invoiceTemplateOverride = None,
      )

      zuoraSoapServiceMock.createPaymentMethod(createPaymentMethod)(any) returns Future(UpdateResult(randomId()))

      val paymentMethod = TestQueriesPaymentMethod(
        id = paymentMethodId,
        mandateId = Some(randomId("mandateId")),
        bankTransferAccountName = Some("Frank Poole"),
        bankCode = Some("000000"),
        bankTransferAccountNumberMask = Some("4444 4444 4444 4444"),
        paymentType = Queries.PaymentMethod.BankTransfer,
      )

      zuoraSoapServiceMock.getPaymentMethod(paymentMethodId)(any) returns Future(paymentMethod)

      sendEmailMock.send(emailData)(any) returns Future.successful(())

      val httpResponse = Unirest
        .post(endpointUrl(s"/user-attributes/me/update-direct-debit/$subscriptionId"))
        .header("Csrf-Token", "nocheck")
        .header(
          "Cookie",
          "gu_paying_member=false; gu_digital_subscriber=true; gu_hide_support_messaging=true; consentUUID=ee459f1e-5d69-4def-a53c-c4a7b4b826f9_13; _ga=GA1.2.1494602535.1668613308; _gcl_au=1.1.1865802744.1673259395; QuantumMetricUserID=7a6ee3603e3f50079f57a932c7016208; gu_user_features_expiry=1676116644464; gu_recurring_contributor=true; GU_mvt_id=414642; GU_country=GB; GU_CO_COMPLETE={\"userType\":\"guest\",\"product\":\"SupporterPlus\"}; gu.contributions.contrib-timestamp=1678788098733; GU_geo_country=GB; _gid=GA1.2.1515141716.1678982877; GU_U=WyIyMDAwNjQ2MTEiLCIiLCJ1c2VyIiwiIiwxNjg2Nzc4NzQ0MTE1LDAsMTY2NzQwNjI0MTAwMCx0cnVlXQ.MC0CFGunCn-eCA9-AaJSyU1NuDQEHLK5AhUAlXRSJU9xBkZS5IcD4EPutZGjk4g; SC_GU_LA=WyJMQSIsIjIwMDA2NDYxMSIsMTY3OTAwMjc0NDExNV0.MC4CFQCI1EHaTXvNALwrnmCP6MlsgaB65QIVAIZb-ZFs38gpRYy1m6AOU65neA11; SC_GU_U=WyIyMDAwNjQ2MTEiLDE2ODY3Nzg3NDQxMTUsIjY5MGVmNmYzMTllYTQwODI4OTBjMGExYTNkOWM5ZTFmIiwiIiwwXQ.MC0CFBUObHNIHJMVasjnW7HHRmeni8GdAhUAkJxvh4IR7UbMc5rL-QWk9J9trTE; GU_AF1=1694900345726",
        )
        .field("accountName", "Frank Poole")
        .field("accountNumber", "4444 4444 4444 4444")
        .field("sortCode", "000000")
        .asString

      httpResponse.getStatus shouldEqual 200

      identityMockClientAndServer.verify(identityRequest)
      subscriptionServiceMock.current(contact)(any) was called
      contactRepositoryMock.get("200067388")(any) was called
      zuoraSoapServiceMock.getAccount(subscription.accountId)(any) wasCalled twice
      zuoraSoapServiceMock.getContact(account.billToId)(any) was called
      zuoraSoapServiceMock.createPaymentMethod(createPaymentMethod)(any) was called
      zuoraSoapServiceMock.getPaymentMethod(paymentMethodId)(any) was called
      sendEmailMock.send(emailData)(any) was called

      supporterProductDataServiceMock wasNever called
      contactRepositoryMock wasNever calledAgain
      subscriptionServiceMock wasNever calledAgain
      zuoraRestServiceMock wasNever calledAgain
      zuoraSoapServiceMock wasNever calledAgain
      databaseServiceMock wasNever called
      sendEmailMock wasNever calledAgain

      val responseBody = httpResponse.getBody
      val responseJson = Json.parse(responseBody)
      responseJson shouldEqual Json.parse("""
          |{
          |   "accountName":"Frank Poole",
          |   "accountNumber":"4444 4444 4444 4444",
          |   "sortCode":"000000"
          |}
          |""".stripMargin)
    }

    "update payment method to credit card and send an email" in {
      val subscriptionId = "A-S00474148"

      val cookies = Map(
        "GU_U" -> "WyIyMDAwNjQ2MTEiLCIiLCJ1c2VyIiwiIiwxNjg2Nzc4NzQ0MTE1LDAsMTY2NzQwNjI0MTAwMCx0cnVlXQ.MC0CFGunCn-eCA9-AaJSyU1NuDQEHLK5AhUAlXRSJU9xBkZS5IcD4EPutZGjk4g",
        "SC_GU_LA" -> "WyJMQSIsIjIwMDA2NDYxMSIsMTY3OTAwMjc0NDExNV0.MC4CFQCI1EHaTXvNALwrnmCP6MlsgaB65QIVAIZb-ZFs38gpRYy1m6AOU65neA11",
        "SC_GU_U" -> "WyIyMDAwNjQ2MTEiLDE2ODY3Nzg3NDQxMTUsIjY5MGVmNmYzMTllYTQwODI4OTBjMGExYTNkOWM5ZTFmIiwiIiwwXQ.MC0CFBUObHNIHJMVasjnW7HHRmeni8GdAhUAkJxvh4IR7UbMc5rL-QWk9J9trTE",
        "consentUUID" -> "ee459f1e-5d69-4def-a53c-c4a7b4b826f9_13",
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
          "WyIyMDAwNjQ2MTEiLDE2ODY3Nzg3NDQxMTUsIjY5MGVmNmYzMTllYTQwODI4OTBjMGExYTNkOWM5ZTFmIiwiIiwwXQ.MC0CFBUObHNIHJMVasjnW7HHRmeni8GdAhUAkJxvh4IR7UbMc5rL-QWk9J9trTE",
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

      val contact = TestContact(
        identityId = "200067388",
        firstName = Some("Frank"),
        lastName = "Poole",
      )
      val emailData = EmailData(
        "frank.poole@amail.com",
        contact.salesforceContactId,
        "payment-method-changed-email",
        Map(
          "first_name" -> "Frank",
          "last_name" -> "Poole",
          "payment_method" -> "card",
          "product_type" -> "Digital Pack",
        ),
      )

      contactRepositoryMock.get("200067388")(any) returns Future(\/.right(Some(contact)))

      val subscription = TestSubscription(
        name = Subscription.Name(subscriptionId),
        plans = CovariantNonEmptyList(TestPaidSubscriptionPlan(productType = "Digital Pack"), Nil),
      )

      subscriptionServiceMock.current(contact)(any) returns Future(List(subscription))

      val customer = TestStripeCustomer(card =
        TestStripeCard(
          `type` = "MasterCard",
          last4 = "2222",
          exp_month = 1,
          exp_year = 2027,
        ),
      )
      ukStripeServiceMock.createCustomerWithStripePaymentMethod("myStripePaymentMethodId")(any) returns
        Future.successful(customer)
      val paymentGateway = mock[PaymentGateway]
      ukStripeServiceMock.paymentIntentsGateway returns paymentGateway
      ukStripeServiceMock.invoiceTemplateOverride returns None

      zuoraSoapServiceMock.createCreditCardPaymentMethod(subscription.accountId, customer, paymentGateway, None)(any) returns
        Future.successful(UpdateResult(randomId("updateId")))

      val account = TestQueriesAccount()
      val paymentMethodId = randomId("paymentMethod")
      val accountWithUpdatedPaymentMethodId = account.copy(defaultPaymentMethodId = Some(paymentMethodId))
      zuoraSoapServiceMock.getAccount(subscription.accountId)(any) returns
        Future(account) andThen
        Future(accountWithUpdatedPaymentMethodId)

      val queriesContact = TestQueriesContact()
      zuoraSoapServiceMock.getContact(account.billToId)(any) returns Future(queriesContact)

      sendEmailMock.send(emailData)(any) returns Future.successful(())

      val httpResponse = Unirest
        .post(endpointUrl(s"/user-attributes/me/update-card/$subscriptionId"))
        .header("Csrf-Token", "nocheck")
        .header(
          "Cookie",
          "gu_paying_member=false; gu_digital_subscriber=true; gu_hide_support_messaging=true; consentUUID=ee459f1e-5d69-4def-a53c-c4a7b4b826f9_13; _ga=GA1.2.1494602535.1668613308; _gcl_au=1.1.1865802744.1673259395; QuantumMetricUserID=7a6ee3603e3f50079f57a932c7016208; gu_user_features_expiry=1676116644464; gu_recurring_contributor=true; GU_mvt_id=414642; GU_country=GB; GU_CO_COMPLETE={\"userType\":\"guest\",\"product\":\"SupporterPlus\"}; gu.contributions.contrib-timestamp=1678788098733; GU_geo_country=GB; _gid=GA1.2.1515141716.1678982877; GU_U=WyIyMDAwNjQ2MTEiLCIiLCJ1c2VyIiwiIiwxNjg2Nzc4NzQ0MTE1LDAsMTY2NzQwNjI0MTAwMCx0cnVlXQ.MC0CFGunCn-eCA9-AaJSyU1NuDQEHLK5AhUAlXRSJU9xBkZS5IcD4EPutZGjk4g; SC_GU_LA=WyJMQSIsIjIwMDA2NDYxMSIsMTY3OTAwMjc0NDExNV0.MC4CFQCI1EHaTXvNALwrnmCP6MlsgaB65QIVAIZb-ZFs38gpRYy1m6AOU65neA11; SC_GU_U=WyIyMDAwNjQ2MTEiLDE2ODY3Nzg3NDQxMTUsIjY5MGVmNmYzMTllYTQwODI4OTBjMGExYTNkOWM5ZTFmIiwiIiwwXQ.MC0CFBUObHNIHJMVasjnW7HHRmeni8GdAhUAkJxvh4IR7UbMc5rL-QWk9J9trTE; GU_AF1=1694900345726",
        )
        .field("stripePaymentMethodID", "myStripePaymentMethodId")
        .field("stripePublicKey", ukStripePublicKey.key)
        .asString

      httpResponse.getStatus shouldEqual 200

      identityMockClientAndServer.verify(identityRequest)
      subscriptionServiceMock.current(contact)(any) was called
      contactRepositoryMock.get("200067388")(any) was called
      ukStripeServiceMock.createCustomerWithStripePaymentMethod("myStripePaymentMethodId")(any) was called
      ukStripeServiceMock.paymentIntentsGateway was called
      ukStripeServiceMock.invoiceTemplateOverride was called
      zuoraSoapServiceMock.createCreditCardPaymentMethod(subscription.accountId, customer, paymentGateway, None)(any) was called
      sendEmailMock.send(emailData)(any) was called

      supporterProductDataServiceMock wasNever called
      contactRepositoryMock wasNever calledAgain
      subscriptionServiceMock wasNever calledAgain
      zuoraRestServiceMock wasNever calledAgain
      ukStripeServiceMock wasNever calledAgain
      zuoraSoapServiceMock wasNever calledAgain
      databaseServiceMock wasNever called
      sendEmailMock wasNever calledAgain

      val responseBody = httpResponse.getBody
      val responseJson = Json.parse(responseBody)
      responseJson shouldEqual Json.parse("""
          |{
          | "type":"MasterCard",
          | "last4":"2222",
          | "expiryMonth":1,
          | "expiryYear":2027
          |}
          |""".stripMargin)
    }
  }
}
