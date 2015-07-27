package controllers

import actions.CommonActions
import models.ApiResponse
import services.AttributeService
import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global

class AttributeController @Inject() (attributeService: AttributeService) extends CommonActions {

  def getMyAttributes = AuthenticatedAction.async { implicit request =>
    ApiResponse{
      attributeService.getAttributes(request.user.id)
    }
  }

  def getAttributes(userId: String) = NoCacheAction.async { implicit request =>
    // TODO use access token to authenticate
    ApiResponse{
      attributeService.getAttributes(userId)
    }
  }
}
