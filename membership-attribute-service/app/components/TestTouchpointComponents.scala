package components

import components.ConfigComponents
import configuration.Config.BackendConfig
import framework.AllComponentTraits
import play.api.BuiltInComponents
import play.api.libs.ws.ning.NingWSComponents

trait TestTouchpointComponents extends TouchpointComponents { self: AllComponentTraits with ConfigComponents =>
  override lazy val sfConfig = BackendConfig.test.salesforceConfig
  override lazy val stage = config.testTouchpointBackendStage

}
