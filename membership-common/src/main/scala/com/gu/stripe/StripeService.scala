package com.gu.stripe

import com.gu.i18n.{Country, Currency}
import com.gu.memsub.util.WebServiceHelper
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.okhttp.RequestRunners._
import com.gu.stripe.Stripe.Deserializer._
import com.gu.stripe.Stripe._
import com.gu.zuora.api.{PaymentGateway, RegionalStripeGateways}
import com.typesafe.config.Config
import okhttp3.Request
import scalaz.syntax.std.boolean._
import scalaz.syntax.std.option._

import scala.concurrent.{ExecutionContext, Future}
import com.gu.zuora.api.StripeTortoiseMediaPaymentIntentsMembershipGateway

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
    version: Option[String],
    variant: Option[String],
)

object StripeServiceConfig {
  def from(config: Config, environmentName: String, stripeAccountCountry: Country, variant: String = "api", version: Option[String] = None) =
    StripeServiceConfig(
      environmentName,
      StripeCredentials.fromConfig(config, variant),
      stripeAccountCountry,
      stripeVersion(config, variant),
      Some(variant),
    )

  def stripeVersion(config: Config, variant: String = "api"): Option[String] =
    config.hasPath(s"stripe.${variant}.version").option(config.getString(s"stripe.${variant}.version"))
}

class StripeService(apiConfig: StripeServiceConfig, client: FutureHttpClient)(implicit ec: ExecutionContext)
    extends WebServiceHelper[StripeObject, Stripe.Error] {
  val wsUrl = "https://api.stripe.com/v1" // Stripe URL is the same in all environments
  val httpClient: FutureHttpClient = client
  val publicKey: String = apiConfig.credentials.publicKey
  val paymentGateway: PaymentGateway = RegionalStripeGateways.getGatewayForCountry(apiConfig.stripeAccountCountry)
  val paymentIntentsGateway: PaymentGateway =
    apiConfig.variant match {
      case Some("tortoise-media") => StripeTortoiseMediaPaymentIntentsMembershipGateway
      case _ => RegionalStripeGateways.getPaymentIntentsGatewayForCountry(apiConfig.stripeAccountCountry)
    }

  override def wsPreExecute(req: Request.Builder)(implicit logPrefix: LogPrefix): Request.Builder = {
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
