package components

import akka.actor.ActorSystem
import com.typesafe.config.Config
import configuration.Config.config
import services.SupporterProductDataService

import scala.concurrent.ExecutionContext

class TouchpointBackends(actorSystem: ActorSystem, config: Config, supporterProductDataServiceOverride: Option[SupporterProductDataService])(implicit executionContext: ExecutionContext) {
  val normal = new TouchpointComponents(config.getString("touchpoint.backend.default"), config, supporterProductDataServiceOverride)(actorSystem, executionContext)
  val test = new TouchpointComponents(config.getString("touchpoint.backend.test"), config, supporterProductDataServiceOverride)(actorSystem, executionContext)
}
