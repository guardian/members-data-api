package com.gu.paypal

import com.typesafe.config.Config

case class PayPalConfig(touchpointEnvironment: String,
                        payPalEnvironment: String,
                        NVPVersion: String,
                        url: String,
                        user: String,
                        password: String,
                        signature: String)

object PayPalConfig {
  def fromConfig(config: Config, environmentName: String) =  {
    PayPalConfig(environmentName,
      config.getString("paypal.paypal-environment"),
      config.getString("paypal.nvp-version"),
      config.getString("paypal.url"),
      config.getString("paypal.user"),
      config.getString("paypal.password"),
      config.getString("paypal.signature")
    )
  }
}