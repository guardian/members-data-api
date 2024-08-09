package com.gu.touchpoint

import com.gu.i18n.Country
import com.gu.identity.IdapiConfig
import com.gu.monitoring.SafeLogging
import com.gu.salesforce.SalesforceConfig
import com.gu.stripe.{BasicStripeServiceConfig, StripeServiceConfig}
import com.gu.zuora.{ZuoraApiConfig, ZuoraRestConfig, ZuoraSoapConfig}

case class TouchpointBackendConfig(
    environmentName: String,
    salesforce: SalesforceConfig,
    stripePatrons: BasicStripeServiceConfig,
    stripeUKMembership: StripeServiceConfig,
    stripeAUMembership: StripeServiceConfig,
    stripeUSMembership: StripeServiceConfig,
    zuoraSoap: ZuoraSoapConfig,
    zuoraRest: ZuoraRestConfig,
    idapi: IdapiConfig,
)

object TouchpointBackendConfig extends SafeLogging {

  def byEnv(environmentName: String, backendsConfig: com.typesafe.config.Config) = {
    val envBackendConf = backendsConfig.getConfig(s"environments.$environmentName")

    TouchpointBackendConfig(
      environmentName,
      SalesforceConfig.from(envBackendConf, environmentName),
      BasicStripeServiceConfig.from(envBackendConf, "patrons"),
      StripeServiceConfig.from(envBackendConf, environmentName, Country.UK), // uk-membership
      StripeServiceConfig.from(envBackendConf, environmentName, Country.Australia, variant = "au-membership"),
      StripeServiceConfig.from(envBackendConf, environmentName, Country.US, variant = "us-membership"),
      ZuoraApiConfig.soap(envBackendConf, environmentName),
      ZuoraApiConfig.rest(envBackendConf, environmentName),
      IdapiConfig.from(envBackendConf, environmentName),
    )
  }
}
