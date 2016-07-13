package controllers

import actions.BackendFromSalesforceAction
import com.gu.memsub.Membership
import com.typesafe.scalalogging.LazyLogging
import models.ApiErrors
import monitoring.CloudWatch
import parsers.Salesforce.{MembershipDeletion, MembershipUpdate, OrgIdMatchingError, ParsingError}
import parsers.{Salesforce => SFParser}
import play.Logger
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.BodyParsers.parse
import play.api.mvc.Results.Ok
import scala.concurrent.Future
import scalaz.std.scalaFuture._
import scalaz.{-\/, OptionT, \/-}

/**
  * There is a workflow rule in Salesforce that triggers a request to this salesforce-hook endpoint
  * on a change to Contact record. If salesforce-hook responds with non-200, Salesfroce will re-queue
  * the request and keep trying.
  *
  * SalesForce Outbound Messaging:
  * https://developer.salesforce.com/docs/atlas.en-us.api.meta/api/sforce_api_om_outboundmessaging.htm?search_text=outbound%20message
  */
class SalesforceHookController extends LazyLogging {
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

  def createAttributes = BackendFromSalesforceAction.async(parse.xml) { request =>
    val touchpoint = request.touchpoint
    val validOrgId = touchpoint.sfOrganisationId
    val attributeService = touchpoint.attrService
    implicit val pf = Membership

    SFParser.parseOutboundMessage(request.body, validOrgId) match {
      case \/-(MembershipDeletion(userId)) =>
        attributeService.delete(userId).map { x =>
          logger.info(s"Successfully deleted user $userId from ${touchpoint.dynamoTable}.")
          metrics.put("Delete", 1)
          ack
        }.recover { case e: Throwable =>
          logger.warn(s"Failed to delete user $userId from ${touchpoint.dynamoTable}. Salesforce should retry.", e)
          ApiErrors.internalError
        }
      case \/-(MembershipUpdate(attrs)) =>
        (for {
          sfId <- OptionT(touchpoint.contactRepo.get(attrs.UserId))
          membershipSubscription <- OptionT(touchpoint.membershipSubscriptionService.get(sfId))
        } yield {
          val tierFromSalesforceWebhook = attrs.Tier
          val tierFromZuora = membershipSubscription.plan.tier.name
          if (tierFromZuora != tierFromSalesforceWebhook) logger.error(s"Differing tier info for $sfId : sf=$tierFromSalesforceWebhook zuora=$tierFromZuora")
          attrs.copy(Tier = tierFromZuora)
        }).run.flatMap { attrsUpdatedWithZuoraOpt =>
          if (attrsUpdatedWithZuoraOpt.isEmpty) logger.error(s"Couldn't update $attrs with information from Zuora")
          attributeService.set(attrsUpdatedWithZuoraOpt.getOrElse(attrs)).map { putItemResult =>
            logger.info(s"Successfully inserted ${attrsUpdatedWithZuoraOpt.getOrElse(attrs)} into ${touchpoint.dynamoTable}.")
            metrics.put("Update", 1)
            ack
          }.recover { case e: Throwable =>
            logger.warn(s"Failed to insert ${attrsUpdatedWithZuoraOpt.getOrElse(attrs)} into ${touchpoint.dynamoTable}. Salesforce should retry.", e)
            ApiErrors.internalError
          }
        }
      case -\/(ParsingError(msg)) =>
        Logger.error(s"Could not parse payload ${request.body}:\n$msg")
        Future(ApiErrors.badRequest(msg))
      case -\/(OrgIdMatchingError(orgId)) =>
        Logger.error(s"Wrong organization Id: $orgId")
        Future(ApiErrors.unauthorized.copy("Wrong organization Id"))
    }
  }
}
