package acceptance.data

import com.gu.i18n.Currency
import models.subscription.{Price, PricingSummary}

object TestPricingSummary {
  def apply(summary: (Currency, Price)*): PricingSummary = PricingSummary(summary.toMap)

  def apply(): PricingSummary = gbp(10)

  def gbp(amount: Double): PricingSummary = TestPricingSummary(Currency.GBP -> Price(amount.toFloat, Currency.GBP))
}
