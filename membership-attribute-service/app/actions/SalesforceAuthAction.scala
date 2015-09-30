package actions

import configuration.Config
import models.ApiErrors.unauthorized
import play.Logger
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.{ActionBuilder, Request, Result}

import scala.concurrent.Future

object SalesforceAuthAction extends ActionBuilder[Request] {
  private val salesforceSecretParam = "secret"

  override def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]): Future[Result] = {
    val secretMatches = request.getQueryString(salesforceSecretParam).contains(Config.salesforceSecret)

    if (secretMatches) {
      block(request)
    }
    else {
      Logger.error(s"Invalid secret for request")
      Future(unauthorized)
    }
  }
}
