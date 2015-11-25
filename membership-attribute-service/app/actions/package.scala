import components.TouchpointComponents
import play.api.mvc.{WrappedRequest, Request, Action}

package object actions {
  val BackendFromCookieAction = Action andThen WithBackendFromCookieAction
  val BackendFromSalesforceAction = Action andThen WithBackendFromSalesforceAction
  class BackendRequest[A](val touchpoint: TouchpointComponents, request: Request[A]) extends WrappedRequest[A](request)
}
