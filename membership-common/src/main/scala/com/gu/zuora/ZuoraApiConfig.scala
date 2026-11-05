package com.gu.zuora

import com.typesafe.config.{Config, ConfigException}
import io.lemonlabs.uri.Uri
import io.lemonlabs.uri.typesafe.dsl._

object ZuoraApiConfig {
  private def required(c: Config, path: String): String = {
    val value = c.getString(path)
    if (value.trim.isEmpty) {
      throw new ConfigException.BadValue(path, "must not be empty")
    }
    value
  }

  def rest(c: Config, environmentName: String) =
    ZuoraRestConfig(
      environmentName,
      required(c, "zuora.api.restUrl"),
      required(c, "zuora.api.oauthTokenUrl"),
      required(c, "zuora.api.clientId"),
      required(c, "zuora.api.clientSecret"),
    )
}

case class ZuoraRestConfig(
    envName: String,
    url: Uri,
    oauthTokenUrl: Uri,
    clientId: String,
    clientSecret: String,
)
