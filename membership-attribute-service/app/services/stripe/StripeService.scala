package services.stripe

import com.gu.stripe.Stripe._
import com.gu.stripe.StripeServiceConfig
import com.gu.zuora.api.{InvoiceTemplate, PaymentGateway, RegionalStripeGateways}

import scala.concurrent.{ExecutionContext, Future}

class StripeService(apiConfig: StripeServiceConfig, val basicStripeService: BasicStripeService)(implicit ec: ExecutionContext) {
  val paymentIntentsGateway: PaymentGateway = RegionalStripeGateways.getPaymentIntentsGatewayForCountry(apiConfig.stripeAccountCountry)
  val invoiceTemplateOverride: Option[InvoiceTemplate] = apiConfig.invoiceTemplateOverride

  def createCustomer(card: String): Future[Customer] = basicStripeService.createCustomer(card)

  def createCustomerWithStripePaymentMethod(stripePaymentMethodID: String): Future[Customer] =
    basicStripeService.createCustomerWithStripePaymentMethod(stripePaymentMethodID)
}
