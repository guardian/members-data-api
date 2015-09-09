package controllers

import com.google.inject.Inject
import models.{ApiErrors, ApiResponse}
import parsers.{Salesforce => SFParser}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.Action
import play.api.mvc.BodyParsers.parse
import services.AttributeService

import scalaz.{-\/, \/-}

class SalesforceHookController @Inject() (attrService: AttributeService) {
  def createAttributes = Action.async(parse.xml) { request =>
    val resp = SFParser.parseOutboundMessage(request.body) match {
      case \/-(attrs) => attrService.setAttributes(attrs)
      case -\/(msg) => ApiResponse.Left(ApiErrors.badRequest(msg))
    }

    ApiResponse(resp)
  }
}
