package acceptance.data.stripe

import acceptance.data.Randoms.randomId
import com.gu.stripe.Stripe.Card

object TestStripeCard {
  def apply(
      id: String = randomId("stripeCardId"),
      `type`: String = "Visa",
      last4: String = "1111",
      exp_month: Int = 10,
      exp_year: Int = 2055,
      country: String = "UK",
  ): Card =
    Card(
      id = id,
      `type` = `type`,
      last4 = last4,
      exp_month = exp_month,
      exp_year = exp_year,
      country = country,
    )
}
