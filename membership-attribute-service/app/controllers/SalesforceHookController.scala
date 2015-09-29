package controllers

import actions.SalesforceAuthAction
import com.google.inject.Inject
import models.ApiErrors
import monitoring.CloudWatch
import parsers.{Salesforce => SFParser}
import play.Logger
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.BodyParsers.parse
import services.AttributeService

import scala.Function.const
import scala.concurrent.Future
import scalaz.{-\/, \/-}

class SalesforceHookController @Inject() (attrService: AttributeService) {

  val metrics = CloudWatch("SalesforceHookController")

  def createAttributes = SalesforceAuthAction.async(parse.xml) { request =>
    SFParser.parseOutboundMessage(request.body) match {
      case \/-(attrs) =>
        metrics.put("Update", 1)
        attrService.setAttributes(attrs).map(const(attrs))
      case -\/(msg) =>
        Logger.error(s"Web hook payload unrecognised ${request.body}")
        Future(ApiErrors.badRequest(msg))
    }
  }
}
