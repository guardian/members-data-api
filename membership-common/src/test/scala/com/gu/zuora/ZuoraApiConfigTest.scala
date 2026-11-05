package com.gu.zuora

import com.typesafe.config.{ConfigException, ConfigFactory}
import io.lemonlabs.uri.typesafe.dsl._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ZuoraApiConfigTest extends AnyFlatSpec with Matchers {

  private val validConfig = ConfigFactory.parseString("""
      |zuora.api {
      |  restUrl = "https://rest.apisandbox.zuora.com/v1"
      |  oauthTokenUrl = "https://rest.apisandbox.zuora.com/oauth/token"
      |  clientId = "client-id"
      |  clientSecret = "client-secret"
      |}
      |""".stripMargin)

  "ZuoraApiConfig" should "load a complete OAuth configuration" in {
    ZuoraApiConfig.rest(validConfig, "CODE") shouldEqual ZuoraRestConfig(
      "CODE",
      "https://rest.apisandbox.zuora.com/v1",
      "https://rest.apisandbox.zuora.com/oauth/token",
      "client-id",
      "client-secret",
    )
  }

  it should "reject blank OAuth configuration values" in {
    Seq(
      "zuora.api.restUrl",
      "zuora.api.oauthTokenUrl",
      "zuora.api.clientId",
      "zuora.api.clientSecret",
    ).foreach { path =>
      val config = ConfigFactory.parseString(s"$path = \" \"").withFallback(validConfig)

      val error = intercept[ConfigException.BadValue](ZuoraApiConfig.rest(config, "CODE"))

      error.getMessage should include(path)
    }
  }
}
