package components

import akka.actor.ActorSystem
import configuration.Config


class NormalTouchpointComponents(system: ActorSystem) extends TouchpointComponents(Config.defaultTouchpointBackendStage)(system)
