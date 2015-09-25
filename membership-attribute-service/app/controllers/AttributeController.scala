package controllers

import javax.inject._

import actions.CommonActions
import configuration.Config
import models.ApiErrors.notFound
import models.Fixtures
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc.Results.Ok
import play.api.mvc.{Action, Cookie}
import services.{AttributeService, AuthenticationService}

import scala.concurrent.Future

class AttributeController @Inject() (attributeService: AttributeService) extends CommonActions {
  val ADFREE_COOKIE_MAX_AGE = 60 * 60 * 6 // 6 hours

  def getMyAttributes =
    if (Config.useFixtures)
      getMyAttributesFromFixtures
    else
      getMyAttributesFromCookie

  private def getMyAttributesFromCookie =
    AuthenticatedAction.async { implicit request =>
      attributeService.getAttributes(request.user.id).map {
        case Some(attrs) => attrs
        case None => notFound
      }
    }

  private def getMyAttributesFromFixtures = Action {
    Fixtures.membershipAttributes
  }

  private def adfreeResponse(adfree: Boolean) =
    Ok(Json.obj("adfree" -> adfree))
      .withCookies(Cookie("gu_adfree_user", adfree.toString, maxAge = Some(ADFREE_COOKIE_MAX_AGE)))

  def adFree =
    Action.async { implicit request =>
      AuthenticationService.authenticatedUserFor(request)
        .fold(Future(adfreeResponse(false))){ minUser =>
          attributeService.getAttributes(minUser.id).map { attrs =>
            adfreeResponse(attrs.exists(_.isPaidTier))
          }
        }
    }
}
