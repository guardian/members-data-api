package actions

import components.TouchpointBackends
import configuration.Config
import play.api.mvc.{ActionRefiner, Request, Result}
import services.IdentityAuthService
import scala.concurrent.{ExecutionContext, Future}

class WithBackendFromCookieAction(touchpointBackends: TouchpointBackends)(implicit ex: ExecutionContext) extends ActionRefiner[Request, BackendRequest] {
  override val executionContext = ex
  override protected def refine[A](request: Request[A]): Future[Either[Result, BackendRequest[A]]] = Future {
    val firstName = IdentityAuthService.username(request).flatMap(_.split(' ').headOption) //Identity checks for test users by first name
    val exists = firstName.exists(Config.testUsernames.isValid)

    val backendConf = if (exists) {
      touchpointBackends.test
    } else {
      touchpointBackends.normal
    }
    Right(new BackendRequest[A](backendConf, request))
  }
}