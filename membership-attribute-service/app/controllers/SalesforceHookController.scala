package controllers

import actions.SalesforceAuthAction
import com.google.inject.Inject
import models.ApiErrors
import parsers.{Salesforce => SFParser}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.BodyParsers.parse
import services.AttributeService

import scala.Function.const
import scala.concurrent.Future
import scalaz.{-\/, \/-}

class SalesforceHookController @Inject() (attrService: AttributeService) {
  def createAttributes = SalesforceAuthAction.async(parse.xml) { request =>
    SFParser.parseOutboundMessage(request.body) match {
      case \/-(attrs) => attrService.setAttributes(attrs).map(const(attrs))
      case -\/(msg) => Future { ApiErrors.badRequest(msg) }
    }
  }
}
