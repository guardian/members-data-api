package controllers

import actions.BackendFromCookieAction
import models.ApiError._
import models.ApiErrors._
import models.Features._
import models.{Attributes, Features}
import monitoring.CloudWatch
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.Result
import services.{AuthenticationService, IdentityAuthService}

import scala.concurrent.Future

class AttributeController {
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val backendAction = BackendFromCookieAction
  lazy val metrics = CloudWatch("AttributesController")

  private def lookup(endpointDescription: String, onSuccess: Attributes => Result, onNotFound: Result = notFound) =
    backendAction.async { request =>
      authenticationService.userId(request).map[Future[Result]] { id =>
        request.attributeService.get(id).map {
          case Some(attrs) =>
            metrics.put(s"$endpointDescription-lookup-successful", 1)
            onSuccess(attrs)
          case None =>
            metrics.put(s"$endpointDescription-user-not-found", 1)
            onNotFound
        }
      }.getOrElse {
        metrics.put(s"$endpointDescription-cookie-auth-failed", 1)
        Future(unauthorized)
      }
    }

  def membership = lookup("membership", identity[Attributes])

  def features = lookup("features",
    onSuccess = Features.fromAttributes,
    onNotFound = Features.unauthenticated
  )
}
