package configuration

import com.gu.identity.cookie.{ProductionKeys, PreProductionKeys}
import com.typesafe.config.ConfigFactory
import controllers.AttributeController
import services.AttributeService

object Config {

  val config = ConfigFactory.load()

  val stage = config.getString("stage")

  val idKeys = if (config.getBoolean("identity.production.keys")) new ProductionKeys else new PreProductionKeys

  val attributeService = new AttributeService
  val attributeController = new AttributeController(attributeService)
}
