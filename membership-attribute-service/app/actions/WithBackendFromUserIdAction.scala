package actions

import components.{NormalTouchpointComponents, TestTouchpointComponents}
import play.api.mvc.{ActionRefiner, Request, Result}

import scala.concurrent.Future
import services.IdentityAuthService
import configuration.Config

object WithBackendFromUserIdAction extends ActionRefiner[Request, BackendRequest] {
  override protected def refine[A](request: Request[A]): Future[Either[Result, BackendRequest[A]]] = Future {
    val firstName = IdentityAuthService.username(request).flatMap(_.split(' ').headOption) //Identity checks for test users by first name
    val exists = firstName.exists(Config.testUsernames.isValid)

    val backendConf = if (exists) {
      TestTouchpointComponents
    } else {
      NormalTouchpointComponents
    }
    Right(new BackendRequest[A](backendConf, request))
  }
}
