package com.gu.zuora.api

import com.gu.i18n.Country

sealed trait PaymentGateway {
  val gatewayName: String
  val forCountry: Option[Country] = None
}
case object DefaultGateway extends PaymentGateway {
  val gatewayName = ""
}
case object StripeUKMembershipGateway extends PaymentGateway {
  val gatewayName = "Stripe Gateway 1"
}
case object StripeUKPaymentIntentsMembershipGateway extends PaymentGateway {
  val gatewayName = "Stripe PaymentIntents GNM Membership"
}
case object StripeAUMembershipGateway extends PaymentGateway {
  val gatewayName = "Stripe Gateway GNM Membership AUS"
  override val forCountry = Some(Country.Australia)
}
case object StripeAUPaymentIntentsMembershipGateway extends PaymentGateway {
  val gatewayName = "Stripe PaymentIntents GNM Membership AUS"
  override val forCountry = Some(Country.Australia)
}
case object GoCardless extends PaymentGateway {
  val gatewayName = "GoCardless"
}
// Temporarily here to enable deseerialisation until it's fully deprovisioned.
case object GoCardlessZuoraInstance extends PaymentGateway {
  val gatewayName = "GoCardless - Zuora Instance"
}
case object PayPal extends PaymentGateway {
  val gatewayName = "PayPal Express"
}

object PaymentGateway {
  private val gatewaysByName = Set(
    StripeUKMembershipGateway,
    StripeAUMembershipGateway,
    StripeUKPaymentIntentsMembershipGateway,
    StripeAUPaymentIntentsMembershipGateway,
    GoCardless,
    GoCardlessZuoraInstance,
    PayPal,
  ).map(g => (g.gatewayName, g)).toMap
  def getByName(gatewayName: String): Option[PaymentGateway] = gatewaysByName.get(gatewayName)
}

object RegionalStripeGateways {
  def getGatewayForCountry(country: Country): PaymentGateway =
    if (country == Country.Australia) StripeAUMembershipGateway else StripeUKMembershipGateway
  def getPaymentIntentsGatewayForCountry(country: Country): PaymentGateway =
    if (country == Country.Australia) StripeAUPaymentIntentsMembershipGateway else StripeUKPaymentIntentsMembershipGateway
}
