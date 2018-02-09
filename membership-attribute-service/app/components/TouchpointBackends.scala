package components

import akka.actor.ActorSystem
import configuration.Config

class TouchpointBackends(actorSystem: ActorSystem){
  val normal =  new TouchpointComponents(Config.defaultTouchpointBackendStage)(actorSystem)
  val test = new TouchpointComponents(Config.testTouchpointBackendStage)(actorSystem)
}
