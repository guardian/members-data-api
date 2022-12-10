package models

import com.gu.i18n.Currency
import org.joda.time.LocalDate
import play.api.libs.json.{Writes, __}

object DynamoSupporterRatePlanItem {
  implicit val currencyWrite: Writes[Currency] = __.write[String].contramap(_.iso)
}

case class DynamoSupporterRatePlanItem(
    subscriptionName: String, // Unique identifier for the subscription
    identityId: String, // Unique identifier for user
    productRatePlanId: String, // Unique identifier for the product in this rate plan
    termEndDate: LocalDate, // Date that this subscription term ends
    contractEffectiveDate: LocalDate, // Date that this subscription started
    cancellationDate: Option[LocalDate], // If this subscription has been cancelled this will be set
    contributionAmount: Option[BigDecimal],
    contributionCurrency: Option[Currency],
)
