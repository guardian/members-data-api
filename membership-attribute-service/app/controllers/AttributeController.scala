package controllers

import javax.inject._

import actions.CommonActions
import configuration.Config
import models.ApiErrors.unauthorized
import models.Fixtures
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.Action
import services.AttributeService

class AttributeController @Inject() (attributeService: AttributeService) extends CommonActions {
  def getMyAttributes =
    if (Config.useFixtures) attributesFromFixtures
    else attributesFromCookie

  private def attributesFromCookie =
    AuthenticatedAction.async { implicit request =>
      attributeService.getAttributes(request.user.id).map {
        case Some(attrs) => attrs
        case None =>  unauthorized("Unauthorized")
      }
    }

  private def attributesFromFixtures = Action {
    Fixtures.membershipAttributes
  }
}
