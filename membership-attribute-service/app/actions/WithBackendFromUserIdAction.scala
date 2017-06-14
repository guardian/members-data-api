package actions

import components.{NormalTouchpointComponents, TestTouchpointComponents}
import play.api.mvc.{ActionRefiner, Request, Result}

import scala.concurrent.Future
import services.IdentityAuthService
import configuration.Config

object WithBackendFromUserIdAction extends ActionRefiner[Request, BackendRequest] {
  override protected def refine[A](request: Request[A]): Future[Either[Result, BackendRequest[A]]] = Future {
    val testUser : Boolean = request.getQueryString("testUser").contains("true");
    val backendConf = if (testUser) {
      TestTouchpointComponents
    } else {
      NormalTouchpointComponents
    }
    Right(new BackendRequest[A](backendConf, request))
  }
}
