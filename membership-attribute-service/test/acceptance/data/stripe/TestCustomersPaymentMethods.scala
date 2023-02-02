package acceptance.data.stripe

import acceptance.data.Randoms.randomId
import services.stripe.Stripe.{CustomersPaymentMethods, StripePaymentMethod, StripePaymentMethodCard}

object TestStripePaymentMethod {
  def apply(
      id: String = randomId("stripePaymentMethodId"),
      card: StripePaymentMethodCard = StripePaymentMethodCard(
        brand = "Visa",
        last4 = "1111",
        exp_month = 1,
        exp_year = 2024,
        country = "UK",
      ),
      customer: String = randomId("stripeCustomer"),
  ): StripePaymentMethod =
    StripePaymentMethod(
      id = id,
      card = card,
      customer = customer,
    )
}

object TestCustomersPaymentMethods {
  def apply(data: List[StripePaymentMethod] = List(TestStripePaymentMethod())): CustomersPaymentMethods = CustomersPaymentMethods(data)
}
