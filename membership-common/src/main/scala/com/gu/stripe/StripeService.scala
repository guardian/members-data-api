package com.gu.stripe

import com.gu.i18n.{Country, Currency}
import com.gu.memsub.util.WebServiceHelper
import com.gu.okhttp.RequestRunners._
import com.gu.stripe.Stripe.Deserializer._
import com.gu.stripe.Stripe._
import com.gu.zuora.api.{InvoiceTemplate, InvoiceTemplates, PaymentGateway, RegionalStripeGateways}
import com.typesafe.config.Config
import okhttp3.Request
import scalaz.syntax.std.boolean._
import scalaz.syntax.std.option._

import scala.concurrent.{ExecutionContext, Future}

case class StripeCredentials(secretKey: String, publicKey: String)

object StripeCredentials {
  def fromConfig(config: Config, variant: String) = StripeCredentials(
    secretKey = config.getString(s"stripe.$variant.key.secret"),
    publicKey = config.getString(s"stripe.$variant.key.public"),
  )
}

case class BasicStripeServiceConfig(stripeCredentials: StripeCredentials, version: Option[String])

object BasicStripeServiceConfig {
  def from(config: Config, variant: String = "api") = BasicStripeServiceConfig(
    StripeCredentials.fromConfig(config, variant),
    StripeServiceConfig.stripeVersion(config, variant),
  )
}

case class StripeServiceConfig(
    envName: String,
    credentials: StripeCredentials,
    stripeAccountCountry: Country,
    invoiceTemplateOverride: Option[InvoiceTemplate],
    version: Option[String],
)

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
    config.hasPath(s"stripe.${variant}.version").option(config.getString(s"stripe.${variant}.version"))
}

class BasicStripeService(config: BasicStripeServiceConfig, client: FutureHttpClient)(implicit ec: ExecutionContext)
    extends WebServiceHelper[StripeObject, Stripe.Error] {
  val wsUrl = "https://api.stripe.com/v1" // Stripe URL is the same in all environments
  val httpClient: FutureHttpClient = client

  override def wsPreExecute(req: Request.Builder): Request.Builder = {
    req.addHeader("Authorization", s"Bearer ${config.stripeCredentials.secretKey}")

    config.version match {
      case Some(version) => {
        logger.info(s"Making a stripe call with version: $version")
        req.addHeader("Stripe-Version", version)
      }
      case None => req
    }
  }

  object Customer {

    @Deprecated
    def create(card: String): Future[Customer] =
      post[Customer]("customers", Map("card" -> Seq(card)))

    def read(customerId: String): Future[Customer] = for {
      customersPaymentMethods <- PaymentMethod.read(customerId)
      customer <- customersPaymentMethods.cardStripeList.data match {
        case Nil => get[Customer](s"customers/$customerId") // fallback for older card tokens
        case _ => Future.successful(Stripe.Customer(customerId, customersPaymentMethods.cardStripeList))
      }
    } yield customer

  }

  object PaymentMethod {
    def read(customerId: String): Future[CustomersPaymentMethods] =
      get[CustomersPaymentMethods](s"payment_methods", "customer" -> customerId, "type" -> "card")
  }

  object Charge {
    def create(amount: Int, currency: Currency, email: String, description: String, cardToken: String, meta: Map[String, String]) =
      post[Charge](
        "charges",
        Map(
          "currency" -> Seq(currency.toString),
          "description" -> Seq(description),
          "amount" -> Seq(amount.toString),
          "receipt_email" -> Seq(email),
          "source" -> Seq(cardToken),
        ) ++ meta.map { case (k, v) => s"metadata[$k]" -> Seq(v) },
      )
  }

  object BalanceTransaction {
    def find(id: String): Future[Stripe.BalanceTransaction] =
      get[BalanceTransaction](id)

    def read(balanceId: String) = {
      find("balance/history/" + balanceId).map(_.some.collect { case b: Stripe.BalanceTransaction =>
        b
      })
    }
  }

  object Event {
    def find(id: String): Future[Stripe.Event[StripeObject]] =
      get[Stripe.Event[StripeObject]](s"events/$id")
  }

  object Subscription {
    def read(id: String): Future[Stripe.Subscription] =
      get[Stripe.Subscription](s"subscriptions/$id", params = ("expand[]", "customer"))
  }
}

class StripeService(apiConfig: StripeServiceConfig, client: FutureHttpClient)(implicit ec: ExecutionContext)
    extends BasicStripeService(BasicStripeServiceConfig(apiConfig.credentials, apiConfig.version), client) {
  val publicKey: String = apiConfig.credentials.publicKey
  val paymentGateway: PaymentGateway = RegionalStripeGateways.getGatewayForCountry(apiConfig.stripeAccountCountry)
  val paymentIntentsGateway: PaymentGateway = RegionalStripeGateways.getPaymentIntentsGatewayForCountry(apiConfig.stripeAccountCountry)
  val invoiceTemplateOverride: Option[InvoiceTemplate] = apiConfig.invoiceTemplateOverride

  override def wsPreExecute(req: Request.Builder): Request.Builder = {
    req.addHeader("Authorization", s"Bearer ${apiConfig.credentials.secretKey}")

    apiConfig.version match {
      case Some(version) => {
        logger.info(s"Making a stripe call with version: $version env: ${apiConfig.envName} country: ${apiConfig.stripeAccountCountry}")
        req.addHeader("Stripe-Version", version)
      }
      case None => req
    }
  }
}
