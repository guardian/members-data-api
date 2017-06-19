package actions

import components.{TestTouchpointComponents, NormalTouchpointComponents}
import models.ApiError._
import models.ApiErrors.unauthorized
import play.api.Logger
import play.api.mvc.{ActionRefiner, Request, Result}
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future
import scalaz.syntax.std.option._


object WithBackendFromSalesforceAction extends ActionRefiner[Request, BackendRequest] {
  private val salesforceSecretParam = "secret"

  override protected def refine[A](request: Request[A]): Future[Either[Result, BackendRequest[A]]] = {
    Future {
      val backend = Seq(TestTouchpointComponents, NormalTouchpointComponents)
        .find(_.sfSecret.some == request.getQueryString(salesforceSecretParam))

      backend.map { conf =>
        Right(new BackendRequest[A](conf, request))
      }.getOrElse {
        Logger.error("Unauthorized call from salesforce: the secret didn't match that of any backend")
        Left(unauthorized)
      }
    }
  }
}
