package acceptance.data.stripe

import acceptance.data.Randoms.randomId
import com.gu.i18n.Currency
import models.DynamoSupporterRatePlanItem
import org.joda.time.LocalDate

object TestDynamoSupporterRatePlanItem {
  def apply(
      identityId: String,
      subscriptionName: String = randomId("dynamoSubscriptionName"),
      productRatePlanId: String = randomId("productRatePlanId"),
      termEndDate: LocalDate = LocalDate.now().minusDays(5).plusYears(1),
      contractEffectiveDate: LocalDate = LocalDate.now().minusDays(5),
      cancellationDate: Option[LocalDate] = None,
      contributionAmount: Option[BigDecimal] = None,
      contributionCurrency: Option[Currency] = None,
  ): DynamoSupporterRatePlanItem = DynamoSupporterRatePlanItem(
    subscriptionName,
    identityId,
    productRatePlanId,
    termEndDate,
    contractEffectiveDate,
    cancellationDate,
    contributionAmount,
    contributionCurrency,
  )
}
