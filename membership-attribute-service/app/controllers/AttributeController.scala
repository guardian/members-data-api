package controllers

import actions.BackendFromCookieAction
import models.ApiError._
import models.ApiErrors._
import models.MembershipAttributes._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc.Results.Ok
import play.api.mvc.{Cookie, Result}
import services.{AuthenticationService, IdentityAuthService}

import scala.concurrent.Future

class AttributeController {
  val ADFREE_COOKIE_MAX_AGE = 60 * 60 * 6 // 6 hours
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val backendAction = BackendFromCookieAction

  def getMyAttributes = backendAction.async { request =>
    authenticationService.userId(request)
      .map[Future[Result]] { id =>
      request.attributeService.get(id).map {
        case Some(attrs) => attrs
        case None => notFound
      }
    }.getOrElse(Future(unauthorized))
  }

  private def adfreeResponse(adfree: Boolean) =
    Ok(Json.obj("adfree" -> adfree, "issuedAt" -> scala.compat.Platform.currentTime))
      .withCookies(
        Cookie("gu_adfree_user", adfree.toString, maxAge = Some(ADFREE_COOKIE_MAX_AGE)),
        Cookie("gu_adblock_message", adfree.toString, maxAge = Some(ADFREE_COOKIE_MAX_AGE))
      )

  def adFree = backendAction.async { implicit request =>
    authenticationService.userId
      .map { id =>
      request.attributeService.get(id).map { attrs =>
        adfreeResponse(attrs.exists(_.isPaidTier))
      }
    }.getOrElse(Future(adfreeResponse(false)))
  }
}
