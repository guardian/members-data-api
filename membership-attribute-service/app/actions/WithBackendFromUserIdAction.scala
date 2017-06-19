package actions

import components.{NormalTouchpointComponents, TestTouchpointComponents}
import play.api.mvc.{ActionRefiner, Request, Result}

import scala.concurrent.Future

object WithBackendFromUserIdAction extends ActionRefiner[Request, BackendRequest] {

  override protected def refine[A](request: Request[A]): Future[Either[Result, BackendRequest[A]]] =
    Future.successful {
      val testUser = request.getQueryString("testUser").contains("true")
      val backendConf = if (testUser) {
        TestTouchpointComponents
      } else {
        NormalTouchpointComponents
      }
      Right(new BackendRequest(backendConf, request))
    }
}
