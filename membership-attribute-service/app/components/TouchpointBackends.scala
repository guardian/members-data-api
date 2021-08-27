package components

import akka.actor.ActorSystem
import configuration.Config

import scala.concurrent.ExecutionContext

class TouchpointBackends(actorSystem: ActorSystem)(implicit executionContext: ExecutionContext) {
  val normal = new TouchpointComponents(Config.defaultTouchpointBackendStage)(actorSystem, executionContext)
  val test = new TouchpointComponents(Config.testTouchpointBackendStage)(actorSystem, executionContext)
}
