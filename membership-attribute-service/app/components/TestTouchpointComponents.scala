package components

import configuration.Config
import configuration.Config.BackendConfig

class TestTouchpointComponents extends TouchpointComponents {
  override lazy val sfConfig = BackendConfig.test.salesforceConfig
  override lazy val stage = Config.testTouchpointBackendStage
}
object TestTouchpointComponents extends TestTouchpointComponents

