package controllers

import configuration.Config
import models.ApiError._
import models.ApiErrors._
import models.Fixtures
import models.MembershipAttributes._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc.Results.Ok
import play.api.mvc.{Action, Cookie, Result}
import services.{AttributeService, AuthenticationService, DynamoAttributeService, IdentityAuthService}

import scala.concurrent.Future

class AttributeController {
  val ADFREE_COOKIE_MAX_AGE = 60 * 60 * 6 // 6 hours
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val attributeService: AttributeService = DynamoAttributeService()

  def getMyAttributes =
    if (Config.useFixtures)
      getMyAttributesFromFixtures
    else
      getMyAttributesFromCookie

  private def getMyAttributesFromCookie = Action.async { implicit request =>
    authenticationService.userId
      .map[Future[Result]] { id =>
        attributeService.get(id).map {
          case Some(attrs) => attrs
          case None => notFound
        }
      }.getOrElse(Future(unauthorized))
  }

  private def getMyAttributesFromFixtures = Action {
    Fixtures.membershipAttributes
  }

  private def adfreeResponse(adfree: Boolean) =
    Ok(Json.obj("adfree" -> adfree))
      .withCookies(Cookie("gu_adfree_user", adfree.toString, maxAge = Some(ADFREE_COOKIE_MAX_AGE)))

  def adFree =
    Action.async { implicit request =>
      authenticationService.userId
        .map { id =>
          attributeService.get(id).map { attrs =>
            adfreeResponse(attrs.exists(_.isPaidTier))
          }
        }.getOrElse(Future(adfreeResponse(false)))
    }
}
