package controllers

import actions.BackendFromSalesforceAction
import com.gu.memsub.Membership
import com.gu.memsub.subsv2.SubscriptionPlan
import com.typesafe.scalalogging.LazyLogging
import models.ApiErrors
import monitoring.CloudWatch
import parsers.Salesforce._
import parsers.{Salesforce => SFParser}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.BodyParsers.parse
import play.api.mvc.Results.Ok
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.subsv2.reads.ChargeListReads._
import scala.concurrent.Future
import scala.util.{Failure, Success}
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

  def processDeletion(membershipDeletion: MembershipDeletion) = {
    val userId = membershipDeletion.userId
    attributeService.delete(userId).map { _ =>
      logger.info(s"Successfully deleted user $userId from ${touchpoint.dynamoTable}.")
      metrics.put("Delete", 1)
      Success
    }.recover { case e: Throwable =>
      logger.warn(s"Failed to delete user $userId from ${touchpoint.dynamoTable}. Salesforce should retry.", e)
      Failure
    }
  }

  def processUpdate(membershipUpdate: MembershipUpdate) = {
    val attrs = membershipUpdate.attributes
    (for {
      sfId <- OptionT(touchpoint.contactRepo.get(attrs.UserId))
      membershipSubscription <- OptionT(touchpoint.subService.current[SubscriptionPlan.Member](sfId).map(_.headOption))
    } yield {
      val tierFromSalesforceWebhook = attrs.Tier
      val tierFromZuora = membershipSubscription.plan.charges.benefit.id
      if (tierFromZuora != tierFromSalesforceWebhook) logger.error(s"Differing tier info for $sfId : sf=$tierFromSalesforceWebhook zuora=$tierFromZuora")
      // If the tier info does not match, we trust the info we get from Zuora, instead of the tier sent to us in the outbound message from Salesforce
      attrs.copy(Tier = tierFromZuora)
    }).run.flatMap { attrsUpdatedWithZuoraOpt =>
      if (attrsUpdatedWithZuoraOpt.isEmpty) logger.error(s"Couldn't update $attrs with information from Zuora")
      attributeService.set(attrsUpdatedWithZuoraOpt.getOrElse(attrs)).map { putItemResult =>
        logger.info(s"Successfully inserted ${attrsUpdatedWithZuoraOpt.getOrElse(attrs)} into ${touchpoint.dynamoTable}.")
        metrics.put("Update", 1)
        Success
      }.recover { case e: Throwable =>
        logger.warn(s"Failed to insert ${attrsUpdatedWithZuoraOpt.getOrElse(attrs)} into ${touchpoint.dynamoTable}. Salesforce should retry.", e)
        Failure
      }
    }
  }

  SFParser.parseOutboundMessage(request.body, validOrgId) match {
    case -\/(ParsingError(msg)) =>
      logger.error(s"Could not parse payload. \n$msg")
      Future(ApiErrors.badRequest(msg))
    case -\/(OrgIdMatchingError(orgId)) =>
      logger.error(s"Wrong organization Id: $orgId")
      Future(ApiErrors.unauthorized.copy("Wrong organization Id"))
    // On successful parse, we should end up with a Seq of Salesforce objects to update
    case \/-(outboundMessageChanges @ Seq(_*)) =>
      logger.info(s"Parsed Salesforce message successfully. Salesforce sent ${outboundMessageChanges.length} objects to update: $outboundMessageChanges")
      // Take the Seq and apply the appropriate action for each notification item, based on its type
      val updates = outboundMessageChanges.map {
        case membershipDelete: MembershipDeletion => processDeletion(membershipDelete)
        case membershipUpdate: MembershipUpdate => processUpdate(membershipUpdate)
      }
      // Gather up the results of the futures and check for failures. Only send a success response to Salesforce if every processUpdate/processDeletion for the message succeeds
      Future.sequence(updates).map { updateSeq => if (updateSeq.contains(Failure)) ApiErrors.internalError else ack }
  }
 }
}
