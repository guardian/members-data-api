package controllers

import actions.BackendFromCookieAction
import models.ApiError._
import models.ApiErrors._
import models.Attributes._
import models.Features
import models.Features._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.Result
import services.{AuthenticationService, IdentityAuthService}

import scala.concurrent.Future

class AttributeController {
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val backendAction = BackendFromCookieAction

  def membership = backendAction.async { request =>
    authenticationService.userId(request).map[Future[Result]] { id =>
      request.attributeService.get(id).map {
        case Some(attrs) => attrs
        case None => notFound
      }
    }.getOrElse(Future(unauthorized))
  }

  def features = backendAction.async { implicit request =>
    authenticationService.userId.map { id =>
      request.attributeService.get(id).map {
        _.map[Result](Features.fromAttributes)
         .getOrElse[Result](Features.unauthenticated)
      }
    }.getOrElse(Future(Features.unauthenticated))
  }
}
