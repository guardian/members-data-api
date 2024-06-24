package models

import com.gu.i18n.Currency.GBP
import com.gu.memsub.BillingPeriod.Month
import com.gu.memsub.Product.SupporterPlus
import com.gu.memsub.Subscription._
import com.gu.memsub.subsv2.ReaderType.Direct
import com.gu.memsub.subsv2.{CovariantNonEmptyList, RatePlan, Subscription, SupporterPlusCharges}
import com.gu.memsub.{PaymentCard, PaymentCardDetails, Price, PricingSummary}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.PersonalPlan
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class ProductsResponseSpec extends Specification with SafeLogging {

  "ProductResponse Json serialisation" should {
    "return a descriptive name in the current plan name field for supporter plus & Guardian Weekly T3 subscriptions" in {
      val accountDetails = AccountDetails(
        contactId = "003UD000009h9KgYAI",
        regNumber = None,
        email = Some("test@thegulocal.com"),
        deliveryAddress = Some(DeliveryAddress(Some("A"), Some("A"), Some("A"), None, Some("SE1 4PU"), Some("United Kingdom"), None, None)),
        subscription = Subscription(
          id = Id("8ad09be48f7af173018f7bd22da22685"),
          name = Name("A-S00889289"),
          accountId = AccountId("8ad09be48f7af173018f7bd22d3e2670"),
          startDate = LocalDate.parse("2024-05-15"),
          acceptanceDate = LocalDate.parse("2024-05-15"),
          termStartDate = LocalDate.parse("2024-05-15"),
          termEndDate = LocalDate.parse("2024-05-15"),
          casActivationDate = None,
          promoCode = None,
          isCancelled = false,
          plans = CovariantNonEmptyList(
            RatePlan(
              id = RatePlanId("8ad09be48f7af173018f7bd22db4268e"),
              productRatePlanId = ProductRatePlanId("8ad081dd8ef57784018ef6e159224bfa"),
              name = "Supporter Plus V2 & Guardian Weekly Domestic - Monthly",
              description = "",
              productName = "Supporter Plus",
              lastChangeType = None,
              productType = "Supporter Plus",
              product = SupporterPlus,
              features = List(),
              charges =
                SupporterPlusCharges(Month, List(PricingSummary(Map(GBP -> Price(10.0f, GBP))), PricingSummary(Map(GBP -> Price(15.0f, GBP))))),
              chargedThrough = Some(LocalDate.parse("2024-06-15")),
              start = LocalDate.parse("2024-05-15"),
              end = LocalDate.parse("2025-05-15"),
            ),
            List(),
          ),
          readerType = Direct,
          gifteeIdentityId = None,
          autoRenew = true,
        ),
        paymentDetails = PaymentDetails(
          subscriberId = "A-S00889289",
          startDate = LocalDate.parse("2024-05-15"),
          customerAcceptanceDate = LocalDate.parse("2024-05-15"),
          chargedThroughDate = Some(LocalDate.parse("2024-06-15")),
          termEndDate = LocalDate.parse("2025-05-15"),
          nextPaymentPrice = Some(2500),
          lastPaymentDate = Some(LocalDate.parse("2024-05-15")),
          nextPaymentDate = Some(LocalDate.parse("2024-06-15")),
          nextInvoiceDate = Some(LocalDate.parse("2024-06-15")),
          remainingTrialLength = -19,
          pendingCancellation = false,
          paymentMethod = Some(
            PaymentCard(
              isReferenceTransaction = true,
              cardType = Some("Visa"),
              paymentCardDetails = Some(PaymentCardDetails("4242", 2, 2029)),
              numConsecutiveFailures = Some(0),
              paymentMethodStatus = Some("Active"),
            ),
          ),
          plan = PersonalPlan("Supporter Plus", Price(25.0f, GBP), "month"),
        ),
        billingCountry = Some(com.gu.i18n.Country.UK),
        stripePublicKey = "pk_test_Qm3CGRdrV4WfGYCpm0sftR0f",
        accountHasMissedRecentPayments = false,
        safeToUpdatePaymentMethod = true,
        isAutoRenew = true,
        alertText = None,
        accountId = "8ad09be48f7af173018f7bd22d3e2670",
        cancellationEffectiveDate = None,
      )
      val expectedResponse = Json.parse("""
          |{
          |  "user" : {
          |    "email" : "test@thegulocal.com"
          |  },
          |  "products" : [ {
          |    "mmaCategory" : "recurringSupport",
          |    "tier" : "Supporter Plus",
          |    "isPaidTier" : true,
          |    "selfServiceCancellation" : {
          |      "isAllowed" : true,
          |      "shouldDisplayEmail" : true,
          |      "phoneRegionsToDisplay" : [ "UK & ROW", "US", "AUS" ]
          |    },
          |    "billingCountry" : "United Kingdom",
          |    "joinDate" : "2024-05-15",
          |    "optIn" : true,
          |    "subscription" : {
          |      "paymentMethod" : "Card",
          |      "card" : {
          |        "last4" : "4242",
          |        "expiry" : {
          |          "month" : 2,
          |          "year" : 2029
          |        },
          |        "type" : "Visa",
          |        "stripePublicKeyForUpdate" : "pk_test_Qm3CGRdrV4WfGYCpm0sftR0f",
          |        "email" : "test@thegulocal.com"
          |      },
          |      "contactId" : "003UD000009h9KgYAI",
          |      "deliveryAddress" : {
          |        "addressLine1" : "A",
          |        "addressLine2" : "A",
          |        "town" : "A",
          |        "postcode" : "SE1 4PU",
          |        "country" : "United Kingdom"
          |      },
          |      "safeToUpdatePaymentMethod" : true,
          |      "start" : "2024-05-15",
          |      "end" : "2025-05-15",
          |      "nextPaymentPrice" : 2500,
          |      "nextPaymentDate" : "2024-06-15",
          |      "potentialCancellationDate" : "2024-06-15",
          |      "inDiscountPeriod": false,
          |      "lastPaymentDate" : "2024-05-15",
          |      "chargedThroughDate" : "2024-06-15",
          |      "renewalDate" : "2025-05-15",
          |      "anniversaryDate" : "2025-05-15",
          |      "cancelledAt" : false,
          |      "subscriptionId" : "A-S00889289",
          |      "trialLength" : -19,
          |      "autoRenew" : true,
          |      "plan" : {
          |        "name" : "Supporter Plus",
          |        "price" : 2500,
          |        "currency" : "£",
          |        "currencyISO" : "GBP",
          |        "billingPeriod" : "month"
          |      },
          |      "currentPlans" : [ {
          |        "name" : null,
          |        "start" : "2024-05-15",
          |        "end" : "2025-05-15",
          |        "shouldBeVisible" : true,
          |        "chargedThrough" : "2024-06-15",
          |        "price" : 2500,
          |        "currency" : "£",
          |        "currencyISO" : "GBP",
          |        "billingPeriod" : "month",
          |        "features" : ""
          |      } ],
          |      "futurePlans" : [ ],
          |      "readerType" : "Direct",
          |      "accountId" : "8ad09be48f7af173018f7bd22d3e2670",
          |      "cancellationEffectiveDate" : null
          |    }
          |  } ]
          }""".stripMargin)
      val user = UserFromToken("test@thegulocal.com", "12345", None, None, None, None, None)
      implicit val logPrefix: LogPrefix = LogPrefix("testLogPrefix")
      val productsResponseJson = Json.toJson(ProductsResponse.from(user, List(accountDetails)))
      productsResponseJson mustEqual expectedResponse
    }
  }

}
