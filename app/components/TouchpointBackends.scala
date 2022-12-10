package components

import akka.actor.ActorSystem
import com.typesafe.config.Config
import services.SupporterProductDataService

import scala.concurrent.ExecutionContext

class TouchpointBackends(actorSystem: ActorSystem, config: Config, supporterProductDataServiceOverride: Option[SupporterProductDataService])(implicit executionContext: ExecutionContext) {
  val normal = new TouchpointComponents(configuration.Config.defaultTouchpointBackendStage, config, supporterProductDataServiceOverride)(actorSystem, executionContext)
  val test = new TouchpointComponents(configuration.Config.testTouchpointBackendStage, config, supporterProductDataServiceOverride)(actorSystem, executionContext)
}
