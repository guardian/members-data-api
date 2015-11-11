import com.gu.membership.touchpoint.TouchpointBackendConfig
import com.gu.monitoring.ServiceMetrics
import configuration.Config.BackendConfig
import play.api.mvc.{WrappedRequest, Request, Action}
import services.AttributeService

package object actions {
  val BackendFromCookieAction = Action andThen WithBackendFromCookieAction
  val TouchpointFromCookieAction = Action andThen WithTouchpointFromCookieAction
  val BackendFromSalesforceAction = Action andThen WithBackendFromSalesforceAction
  class BackendRequest[A](val backendConfig: BackendConfig, val attributeService: AttributeService, request: Request[A]) extends WrappedRequest[A](request)
  class TouchpointRequest[A](val config: TouchpointBackendConfig, val metrics: String => ServiceMetrics, request: Request[A]) extends WrappedRequest[A](request)
}
