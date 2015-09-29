package controllers

import actions.SalesforceAuthAction
import models.ApiErrors
import monitoring.CloudWatch
import parsers.{Salesforce => SFParser}
import play.Logger
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.BodyParsers.parse
import play.api.mvc.Results.Ok
import services.{AttributeService, DynamoAttributeService}

import scala.Function.const
import scala.concurrent.Future
import scalaz.{-\/, \/-}

class SalesforceHookController {
  lazy val attributeService: AttributeService = DynamoAttributeService()
  val metrics = CloudWatch("SalesforceHookController")
  def createAttributes = SalesforceAuthAction.async(parse.xml) { request =>
    SFParser.parseOutboundMessage(request.body) match {
      case \/-(attrs) if attrs.tier.isEmpty =>
        metrics.put("Delete", 1)
        attributeService.delete(attrs.userId).map(const(Ok))
      case \/-(attrs) =>
        metrics.put("Update", 1)
        attributeService.set(attrs).map(const(attrs))
      case -\/(msg) =>
        Logger.error(s"Web hook payload unrecognised ${request.body}")
        Future { ApiErrors.badRequest(msg) }
    }
  }
}
