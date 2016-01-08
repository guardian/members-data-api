package components
import configuration.Config
import play.libs.Akka.system
import play.api.Play.current

object TestTouchpointComponents extends TouchpointComponents(Config.testTouchpointBackendStage)(system)
