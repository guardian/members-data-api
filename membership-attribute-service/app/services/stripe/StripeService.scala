package services.stripe

import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.stripe.Stripe._
import com.gu.stripe.StripeServiceConfig
import com.gu.zuora.api.{PaymentGateway, RegionalStripeGateways}

import scala.concurrent.{ExecutionContext, Future}

class StripeService(apiConfig: StripeServiceConfig, val basicStripeService: BasicStripeService) {
  val paymentIntentsGateway: PaymentGateway = RegionalStripeGateways.getPaymentIntentsGatewayForCountry(apiConfig.stripeAccountCountry)

  def createCustomer(card: String)(implicit logPrefix: LogPrefix): Future[Customer] = basicStripeService.createCustomer(card)

  def createCustomerWithStripePaymentMethod(stripePaymentMethodID: String)(implicit logPrefix: LogPrefix): Future[Customer] =
    basicStripeService.createCustomerWithStripePaymentMethod(stripePaymentMethodID)
}
