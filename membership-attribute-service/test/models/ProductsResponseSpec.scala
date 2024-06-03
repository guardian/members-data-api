package models

import com.gu.i18n.Currency.GBP
import com.gu.memsub.BillingPeriod.Month
import com.gu.memsub.{PaymentCard, PaymentCardDetails, Price, PricingSummary}
import com.gu.memsub.Product.SupporterPlus
import com.gu.memsub.Subscription.{AccountId, Id, Name, ProductRatePlanId, RatePlanId}
import com.gu.memsub.subsv2.{CovariantNonEmptyList, RatePlan, Subscription, SupporterPlusCharges}
import com.gu.memsub.subsv2.ReaderType.Direct
import com.gu.monitoring.{SafeLogger, SafeLogging}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.PersonalPlan
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class ProductsResponseSpec extends Specification with SafeLogging{

  "ProductResponse" should {
    "handle a supporter plus & Guardian Weekly T3 subscription correctly" in {
      val accountDetails = AccountDetails(
        "003UD000009h9KgYAI",
        None,
        Some("test@thegulocal.com"),
        Some(DeliveryAddress(Some("A"), Some("A"), Some("A"), None, Some("SE1 4PU"), Some("United Kingdom"), None, None)),
        Subscription(
          Id("8ad09be48f7af173018f7bd22da22685"),
          Name("A-S00889289"),
          AccountId("8ad09be48f7af173018f7bd22d3e2670"),
          LocalDate.parse("2024-05-15"),
          LocalDate.parse("2024-05-15"),
          LocalDate.parse("2024-05-15"),
          LocalDate.parse("2024-05-15"),
          None,
          None,
          false,
          CovariantNonEmptyList(
            RatePlan(
              RatePlanId("8ad09be48f7af173018f7bd22db4268e"),
              ProductRatePlanId("8ad081dd8ef57784018ef6e159224bfa"),
              "Supporter Plus V2 & Guardian Weekly Domestic - Monthly",
              "",
              "Supporter Plus",
              None,
              "Supporter Plus",
              SupporterPlus,
              List(),
              SupporterPlusCharges(Month, List(PricingSummary(Map(GBP -> Price(10.0f, GBP))), PricingSummary(Map(GBP -> Price(15.0f, GBP))))),
              Some(LocalDate.parse("2024-06-15")),
              LocalDate.parse("2024-05-15"),
              LocalDate.parse("2025-05-15"),
            ),
            List(),
          ),
          Direct,
          None,
          true,
        ),
        PaymentDetails(
          "A-S00889289",
          LocalDate.parse("2024-05-15"),
          LocalDate.parse("2024-05-15"),
          Some(LocalDate.parse("2024-06-15")),
          LocalDate.parse("2025-05-15"),
          Some(2500),
          Some(LocalDate.parse("2024-05-15")),
          Some(LocalDate.parse("2024-06-15")),
          -19,
          false,
          Some(PaymentCard(true, Some("Visa"), Some(PaymentCardDetails("4242", 2, 2029)), Some(0), Some("Active"))),
          PersonalPlan("Supporter Plus", Price(25.0f, GBP), "month"),
        ),
        Some(com.gu.i18n.Country.UK),
        "pk_test_Qm3CGRdrV4WfGYCpm0sftR0f",
        false,
        true,
        true,
        None,
        "8ad09be48f7af173018f7bd22d3e2670",
        None,
      )
      val user = UserFromToken("test@thegulocal.com", "12345", None, None, None, None, None)
      implicit val logPrefix: LogPrefix = LogPrefix("testLogPrefix")
      val productsResponseJson = Json.toJson(ProductsResponse.from(user, List(accountDetails)))
      logger.info(s"productsResponseJson: ${Json.prettyPrint(productsResponseJson)}")
      true mustEqual true
    }
  }

}
