package com.gu.identity

import com.typesafe.config.Config

case class IdapiConfig(
    url: String,
    token: String,
)

object IdapiConfig {
  def from(config: Config, environmentName: String) = IdapiConfig(
    url = config.getString("identity.apiUrl"),
    token = config.getString("identity.apiToken"),
  )
}
