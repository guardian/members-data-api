package acceptance.data.stripe

import acceptance.data.Randoms.randomId
import com.gu.stripe.Stripe
import com.gu.stripe.Stripe.{Card, Customer, StripeList}

object TestStripeCustomer {
  def apply(id: String = randomId("stripeCustomer"), card: Card = TestStripeCard()): Stripe.Customer = Customer(id, StripeList(1, Seq(card)))
}
