package com.gu.touchpoint

import com.gu.i18n.Country
import com.gu.identity.IdapiConfig
import com.gu.paypal.PayPalConfig
import com.gu.salesforce.SalesforceConfig
import com.gu.stripe.{BasicStripeServiceConfig, StripeServiceConfig}
import com.gu.zuora.{ZuoraApiConfig, ZuoraRestConfig, ZuoraSoapConfig}
import com.gu.monitoring.SafeLogger

case class TouchpointBackendConfig(
  environmentName: String,
  salesforce: SalesforceConfig,
  stripePatrons: BasicStripeServiceConfig,
  stripeUKMembership: StripeServiceConfig,
  stripeUKContributions: StripeServiceConfig,
  stripeAUMembership: StripeServiceConfig,
  stripeAUContributions: StripeServiceConfig,
  payPal: PayPalConfig,
  zuoraSoap: ZuoraSoapConfig,
  zuoraRest: ZuoraRestConfig,
  idapi: IdapiConfig
)

object TouchpointBackendConfig {

  sealed abstract class BackendType(val name: String)
  object BackendType {
    object Default extends BackendType("default")
    object Testing extends BackendType("test")
  }

  def byType(typ: BackendType = BackendType.Default, config: com.typesafe.config.Config) = {
    val backendsConfig = config.getConfig("touchpoint.backend")
    val environmentName = backendsConfig.getString(typ.name)

    val touchpointBackendConfig = byEnv(environmentName, backendsConfig)

    SafeLogger.info(s"TouchPoint config - $typ: config=${touchpointBackendConfig.hashCode}")

    touchpointBackendConfig
  }

  def byEnv(environmentName: String, backendsConfig: com.typesafe.config.Config) = {
    val envBackendConf = backendsConfig.getConfig(s"environments.$environmentName")

    TouchpointBackendConfig(
      environmentName,
      SalesforceConfig.from(envBackendConf, environmentName),
      BasicStripeServiceConfig.from(envBackendConf, "patrons"),
      StripeServiceConfig.from(envBackendConf, environmentName, Country.UK),                      // uk-membership
      StripeServiceConfig.from(envBackendConf, environmentName, Country.UK, variant = "giraffe"), // uk-contributions
      StripeServiceConfig.from(envBackendConf, environmentName, Country.Australia, variant = "au-membership"),
      StripeServiceConfig.from(envBackendConf, environmentName, Country.Australia, variant = "au-contributions"),
      PayPalConfig.fromConfig(envBackendConf, environmentName),
      ZuoraApiConfig.soap(envBackendConf, environmentName),
      ZuoraApiConfig.rest(envBackendConf, environmentName),
      IdapiConfig.from(envBackendConf, environmentName)
    )
  }
}
