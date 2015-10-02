package controllers

import actions.SalesforceAuthAction
import configuration.Config
import models.ApiErrors
import monitoring.CloudWatch
import parsers.Salesforce.{MembershipUpdate, MembershipDeletion, OrgIdMatchingError, ParsingError}
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

  private val ack = Ok(
    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
      <soapenv:Body>
        <notificationsResponse xmlns="http://soap.sforce.com/2005/09/outbound">
          <Ack>true</Ack>
        </notificationsResponse>
      </soapenv:Body>
    </soapenv:Envelope>
  )

  def createAttributes = SalesforceAuthAction.async(parse.xml) { request =>
    SFParser.parseOutboundMessage(request.body, Config.Salesforce.organizationId) match {
      case \/-(MembershipDeletion(userId))  =>
        metrics.put("Delete", 1)
        attributeService.delete(userId).map(const(ack))
      case \/-(MembershipUpdate(attrs)) =>
        metrics.put("Update", 1)
        attributeService.set(attrs).map(const(ack))
      case -\/(ParsingError(msg)) =>
        Logger.error(s"Could not parse payload ${request.body}:\n$msg")
        Future { ApiErrors.badRequest(msg) }
      case -\/(OrgIdMatchingError(orgId)) =>
        Logger.error(s"Wrong organization Id: $orgId")
        Future { ApiErrors.unauthorized.copy("Wrong organization Id") }
    }
  }
}
