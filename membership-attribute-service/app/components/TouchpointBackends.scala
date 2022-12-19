package components

import akka.actor.ActorSystem
import com.typesafe.config.Config
import configuration.Stage
import services.SupporterProductDataService

import scala.concurrent.ExecutionContext

class TouchpointBackends(actorSystem: ActorSystem, config: Config, supporterProductDataServiceOverride: Option[SupporterProductDataService])(implicit
    executionContext: ExecutionContext,
) {
  private val defaultStage = Stage(config.getString("touchpoint.backend.default"))
  val normal = new TouchpointComponents(defaultStage, config, supporterProductDataServiceOverride)(
    actorSystem,
    executionContext,
  )
  private val testStage = Stage(config.getString("touchpoint.backend.test"))
  val test =
    new TouchpointComponents(testStage, config, supporterProductDataServiceOverride)(actorSystem, executionContext)
}
