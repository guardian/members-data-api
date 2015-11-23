package components
import configuration.Config.BackendConfig
import framework.AllComponentTraits

trait TestTouchpointComponents extends TouchpointComponents { self: AllComponentTraits =>
  override lazy val sfConfig = BackendConfig.test.salesforceConfig
  override lazy val stage = config.testTouchpointBackendStage

}
