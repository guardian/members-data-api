package controllers

import javax.inject._

import actions.CommonActions
import models.ApiError
import play.api.libs.concurrent.Execution.Implicits._
import services.AttributeService

class AttributeController @Inject() (attributeService: AttributeService) extends CommonActions {
  def getMyAttributes = AuthenticatedAction.async { implicit request =>
    attributeService.getAttributes(request.user.id).map {
      case None => ApiError(
        message = "Unauthorized",
        details = "Failed to authenticate",
        statusCode = 401
      )
      case Some(attrs) => attrs
    }
  }
}
