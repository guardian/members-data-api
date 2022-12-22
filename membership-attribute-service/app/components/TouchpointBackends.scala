package components

import akka.actor.ActorSystem
import com.typesafe.config.Config
import configuration.Stage
import monitoring.CreateMetrics
import services.SupporterProductDataService

import scala.concurrent.ExecutionContext

class TouchpointBackends(
    actorSystem: ActorSystem,
    config: Config,
    createMetrics: CreateMetrics,
    supporterProductDataServiceOverride: Option[SupporterProductDataService],
)(implicit
    executionContext: ExecutionContext,
) {
  private val defaultStage = Stage(config.getString("touchpoint.backend.default"))
  val normal = new TouchpointComponents(defaultStage, createMetrics, config, supporterProductDataServiceOverride)(
    actorSystem,
    executionContext,
  )
  private val testStage = Stage(config.getString("touchpoint.backend.test"))
  val test =
    new TouchpointComponents(testStage, createMetrics, config, supporterProductDataServiceOverride)(actorSystem, executionContext)
}
