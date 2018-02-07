package components
import akka.actor.ActorSystem
import configuration.Config

class TestTouchpointComponents(system:ActorSystem) extends TouchpointComponents(Config.testTouchpointBackendStage)(system)
