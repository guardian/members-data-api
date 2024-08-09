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
case object StripeUSMembershipGateway extends PaymentGateway {
  val gatewayName = "Stripe Gateway GNM Membership USA"
  override val forCountry = Some(Country.US)
}
case object StripeUSPaymentIntentsMembershipGateway extends PaymentGateway {
  val gatewayName = "Stripe PaymentIntents GNM Membership USA"
  override val forCountry = Some(Country.US)
}
case object GoCardlessGateway extends PaymentGateway {
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
    StripeUSMembershipGateway,
    StripeUKPaymentIntentsMembershipGateway,
    StripeAUPaymentIntentsMembershipGateway,
    StripeUSPaymentIntentsMembershipGateway,
    GoCardlessGateway,
    GoCardlessZuoraInstance,
    PayPal,
  ).map(g => (g.gatewayName, g)).toMap
  def getByName(gatewayName: String): Option[PaymentGateway] = gatewaysByName.get(gatewayName)
}

object RegionalStripeGateways {
  def getGatewayForCountry(country: Country): PaymentGateway = country match {
    case Country.Australia => StripeAUMembershipGateway
    case Country.US => StripeUSMembershipGateway
    case _ => StripeUKMembershipGateway
  }
  def getPaymentIntentsGatewayForCountry(country: Country): PaymentGateway = country match {
    case Country.Australia => StripeAUPaymentIntentsMembershipGateway
    case Country.US => StripeUSPaymentIntentsMembershipGateway
    case _ => StripeUKPaymentIntentsMembershipGateway
  }
}
