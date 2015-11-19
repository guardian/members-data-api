package components

import configuration.Config
import configuration.Config.BackendConfig

class NormalTouchpointComponents extends TouchpointComponents {
  override lazy val sfConfig = BackendConfig.default.salesforceConfig
  override lazy val stage = Config.defaultTouchpointBackendStage
}
object NormalTouchpointComponents extends NormalTouchpointComponents
