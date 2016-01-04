package components
import configuration.Config
import play.libs.Akka.system
import play.api.Play.current

object NormalTouchpointComponents extends TouchpointComponents(Config.defaultTouchpointBackendStage)(system)
