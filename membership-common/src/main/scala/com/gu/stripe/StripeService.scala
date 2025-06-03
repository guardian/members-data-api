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
import com.gu.zuora.api.StripeAUPaymentIntentsMembershipGateway
import com.gu.zuora.api.StripeUKPaymentIntentsMembershipGateway

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
    variant: String,
)

object StripeServiceConfig {
  def from(config: Config, environmentName: String, stripeAccountCountry: Country, variant: String = "api", version: Option[String] = None) =
    StripeServiceConfig(
      environmentName,
      StripeCredentials.fromConfig(config, variant),
      stripeAccountCountry,
      stripeVersion(config, variant),
      variant,
    )

  def stripeVersion(config: Config, variant: String = "api"): Option[String] =
    config.hasPath(s"stripe.${variant}.version").option(config.getString(s"stripe.${variant}.version"))
}