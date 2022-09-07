package models

import com.gu.i18n.Currency
import org.joda.time.LocalDate
import play.api.libs.functional.syntax.unlift
import play.api.libs.json.{OWrites, Writes, __}
import play.api.libs.functional.syntax._

case class ContributionAmount(amount: BigDecimal, currency: Currency)

object ContributionAmount {
    implicit val currencyWrite: Writes[Currency] = __.write[String].contramap(_.iso)

    implicit val jsWrite: OWrites[ContributionAmount] = (
      (__ \ "amount").write[BigDecimal] and
        (__ \ "currency").write[Currency]
      )(unlift(ContributionAmount.unapply))
}

case class DynamoSupporterRatePlanItem(
    subscriptionName: String, // Unique identifier for the subscription
    identityId: String, // Unique identifier for user
    productRatePlanId: String, // Unique identifier for the product in this rate plan
    termEndDate: LocalDate, // Date that this subscription term ends
    contractEffectiveDate: LocalDate, // Date that this subscription started
    contributionAmount: Option[ContributionAmount]
)
