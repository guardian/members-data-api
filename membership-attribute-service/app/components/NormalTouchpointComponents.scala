package components
import configuration.Config.BackendConfig
import play.api.BuiltInComponents
import play.api.libs.ws.ning.NingWSComponents

trait NormalTouchpointComponents extends TouchpointComponents { self: BuiltInComponents with ConfigComponents with NingWSComponents =>
  override lazy val sfConfig = BackendConfig.default.salesforceConfig
  override lazy val stage = config.defaultTouchpointBackendStage
}
