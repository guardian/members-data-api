package actions

import components.{NormalTouchpointComponents, TestTouchpointComponents}
import play.api.mvc.{ActionRefiner, Request, Result}
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future

object WithBackendFromUserIdAction extends ActionRefiner[Request, BackendRequest] {

  override protected def refine[A](request: Request[A]): Future[Either[Result, BackendRequest[A]]] =
    Future.successful {
      val testUser : Boolean = request.getQueryString("testUser").contains("true");
      val backendConf = if (testUser) {
        TestTouchpointComponents
      } else {
        NormalTouchpointComponents
      }
      Right(new BackendRequest[A](backendConf, request))
    }
}
