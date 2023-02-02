package services.stripe

import com.gu.i18n.Country
import com.typesafe.config.Config
import scalaz.Scalaz.ToBooleanOpsFromBoolean
import services.stripe.Stripe._
import services.zuora.api.{InvoiceTemplate, InvoiceTemplates, PaymentGateway, RegionalStripeGateways}

import scala.concurrent.{ExecutionContext, Future}

class StripeService(apiConfig: StripeServiceConfig, val basicStripeService: BasicStripeService)(implicit ec: ExecutionContext) {
  val paymentIntentsGateway: PaymentGateway = RegionalStripeGateways.getPaymentIntentsGatewayForCountry(apiConfig.stripeAccountCountry)
  val invoiceTemplateOverride: Option[InvoiceTemplate] = apiConfig.invoiceTemplateOverride

  def createCustomer(card: String): Future[Customer] = basicStripeService.createCustomer(card)

  def createCustomerWithStripePaymentMethod(stripePaymentMethodID: String): Future[Customer] =
    basicStripeService.createCustomerWithStripePaymentMethod(stripePaymentMethodID)
}

object StripeServiceConfig {
  def from(config: Config, environmentName: String, stripeAccountCountry: Country, variant: String = "api", version: Option[String] = None) =
    StripeServiceConfig(
      environmentName,
      StripeCredentials.fromConfig(config, variant),
      stripeAccountCountry,
      InvoiceTemplates.fromConfig(config.getConfig("zuora.invoiceTemplateIds")).find(_.country == stripeAccountCountry),
      stripeVersion(config, variant),
    )

  def stripeVersion(config: Config, variant: String = "api"): Option[String] =
    config.hasPath(s"stripe.$variant.version").option(config.getString(s"stripe.${variant}.version"))
}

case class StripeServiceConfig(
    envName: String,
    credentials: StripeCredentials,
    stripeAccountCountry: Country,
    invoiceTemplateOverride: Option[InvoiceTemplate],
    version: Option[String],
)

object StripeCredentials {
  def fromConfig(config: Config, variant: String) = StripeCredentials(
    secretKey = config.getString(s"stripe.$variant.key.secret"),
    publicKey = config.getString(s"stripe.$variant.key.public"),
  )
}

case class StripeCredentials(secretKey: String, publicKey: String)
