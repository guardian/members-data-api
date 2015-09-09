package controllers

import javax.inject._

import actions.CommonActions
import models.ApiResponse
import services.AttributeService

import scala.concurrent.ExecutionContext.Implicits.global

class AttributeController @Inject() (attributeService: AttributeService) extends CommonActions {
  def getMyAttributes = AuthenticatedAction.async { implicit request =>
    ApiResponse {
      attributeService.getAttributes(request.user.id)
    }
  }
}
