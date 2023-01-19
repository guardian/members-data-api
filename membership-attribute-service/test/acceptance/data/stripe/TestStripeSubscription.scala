package acceptance.data.stripe

import acceptance.data.Randoms.randomId
import com.gu.i18n.Currency
import com.gu.stripe.Stripe
import com.gu.stripe.Stripe.{SubscriptionCustomer, SubscriptionPlan}
import org.joda.time.LocalDate

object TestStripeSubscription {
  def apply(
      id: String = randomId("stripeSubscriptionId"),
      created: LocalDate = LocalDate.now().minusDays(10),
      currentPeriodStart: LocalDate = LocalDate.now().minusDays(8),
      currentPeriodEnd: LocalDate = LocalDate.now().minusDays(8).plusYears(1),
      cancelledAt: Option[LocalDate] = None,
      cancelAtPeriodEnd: Boolean = true,
      customer: SubscriptionCustomer = SubscriptionCustomer(
        randomId("stripeCustomerId"),
        randomId("email"),
      ),
      plan: SubscriptionPlan = SubscriptionPlan(
        id = randomId("stripePlanId"),
        amount = 1000,
        interval = "year",
        currency = Currency.GBP,
      ),
      status: String = "find_me_a_valid_status",
  ) = Stripe.Subscription(
    id,
    created,
    currentPeriodStart,
    currentPeriodEnd,
    cancelledAt,
    cancelAtPeriodEnd,
    customer,
    plan,
    status,
  )
}
