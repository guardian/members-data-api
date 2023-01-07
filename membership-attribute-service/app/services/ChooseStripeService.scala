package services

import com.gu.i18n.Country
import com.gu.stripe.StripeService
import com.gu.zuora.api.{PaymentGateway, RegionalStripeGateways}

class ChooseStripeService(stripeServicesByPaymentGateway: Map[PaymentGateway, StripeService], ukStripeService: StripeService) {
  def forCountry(country: Option[Country]): StripeService = {
    country
      .map(RegionalStripeGateways.getGatewayForCountry)
      .flatMap(stripeServicesByPaymentGateway.get)
      .getOrElse(ukStripeService)
  }
}
