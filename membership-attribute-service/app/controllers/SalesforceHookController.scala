package controllers

import actions.SalesforceAuthAction
import models.ApiErrors
import parsers.{Salesforce => SFParser}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.BodyParsers.parse
import play.api.mvc.Results.Ok
import services.{AttributeService, DynamoAttributeService}

import scala.Function.const
import scala.concurrent.Future
import scalaz.{-\/, \/-}

class SalesforceHookController {
  lazy val attributeService: AttributeService = DynamoAttributeService
  def createAttributes = SalesforceAuthAction.async(parse.xml) { request =>
    SFParser.parseOutboundMessage(request.body) match {
      case \/-(attrs) if attrs.tier.isEmpty => attributeService.delete(attrs.userId).map(const(Ok))
      case \/-(attrs) => attributeService.set(attrs).map(const(attrs))
      case -\/(msg) => Future { ApiErrors.badRequest(msg) }
    }
  }
}
