package com.gu.zuora

import io.lemonlabs.uri.Uri
import io.lemonlabs.uri.typesafe.dsl._

object ZuoraApiConfig {
  def rest(c: com.typesafe.config.Config, environmentName: String) =
    ZuoraRestConfig(
      environmentName,
      c.getString("zuora.api.restUrl"),
      c.getString("zuora.api.oauthTokenUrl"),
      c.getString("zuora.api.clientId"),
      c.getString("zuora.api.clientSecret"),
    )
}

case class ZuoraRestConfig(
    envName: String,
    url: Uri,
    oauthTokenUrl: Uri,
    clientId: String,
    clientSecret: String,
)
