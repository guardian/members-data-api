package actions

import components.TouchpointBackends
import filters.AddGuIdentityHeaders
import play.api.mvc.{ActionRefiner, Request, Result}
import services.IdentityAuthService

import scala.concurrent.{ExecutionContext, Future}

class WithBackendFromCookieAction(touchpointBackends: TouchpointBackends)(implicit ex: ExecutionContext) extends ActionRefiner[Request, BackendRequest] {
  override val executionContext = ex
  override protected def refine[A](request: Request[A]): Future[Either[Result, BackendRequest[A]]] = Future {

    val backendConf = if (AddGuIdentityHeaders.isTestUser(IdentityAuthService.username(request))) {
      touchpointBackends.test
    } else {
      touchpointBackends.normal
    }
    Right(new BackendRequest[A](backendConf, request))
  }
}