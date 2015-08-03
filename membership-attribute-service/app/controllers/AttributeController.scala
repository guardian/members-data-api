package controllers

import actions.CommonActions
import models.{MembershipAttributes, ApiResponse}
import play.api.mvc.BodyParsers
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

  def setMyAttributes = AuthenticatedAction.async[MembershipAttributes](BodyParsers.parse.json[MembershipAttributes]) { implicit request =>
    ApiResponse {
      attributeService.setAttributes(request.user.id, request.body)
    }
  }

  def setAttributes(userId: String) = NoCacheAction.async[MembershipAttributes](BodyParsers.parse.json[MembershipAttributes]) { implicit request =>
    // TODO use access token to authenticate
    ApiResponse {
      attributeService.setAttributes(userId, request.body)
    }
  }
}
