package actions

import configuration.Config
import models.ApiErrors.unauthorized
import play.api.mvc.{ActionBuilder, Request, Result}

import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._

object SalesforceAuthAction extends ActionBuilder[Request] {
  private val salesforceSecretParam = "secret"

  override def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]): Future[Result] = {
    val secretMatches = request.getQueryString(salesforceSecretParam).contains(Config.salesforceSecret)
    if (secretMatches) block(request) else Future { unauthorized }
  }
}
