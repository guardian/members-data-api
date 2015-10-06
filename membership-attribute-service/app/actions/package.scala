import configuration.Config.BackendConfig
import play.api.mvc.{WrappedRequest, Request, Action}
import services.AttributeService

package object actions {
  val BackendFromCookieAction = Action andThen WithBackendFromCookieAction
  val BackendFromSalesforceAction = Action andThen WithBackendFromSalesforceAction
  class BackendRequest[A](val backendConfig: BackendConfig, val attributeService: AttributeService, request: Request[A]) extends WrappedRequest[A](request)
}
