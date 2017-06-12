package controllers

import actions.BackendFromSalesforceAction
import com.gu.memsub.Membership
import com.gu.memsub.subsv2.SubscriptionPlan
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.typesafe.scalalogging.LazyLogging
import models.{ApiErrors, Attributes}
import monitoring.Metrics
import parsers.Salesforce._
import parsers.{Salesforce => SFParser}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.BodyParsers.parse
import play.api.mvc.Results.Ok

import scala.concurrent.Future
import scala.util.Failure
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
  val metrics = Metrics("SalesforceHookController")

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
  val requestId = scala.util.Random.nextInt

  implicit val pf = Membership

  logger.info(s"Called from Saleforce to modify the Members Data API. Request Id: $requestId")

  def deleteMemberRecord(membershipDeletion: MembershipDeletion): Future[Object] = {
    val userId = membershipDeletion.userId
    attributeService.delete(userId).map { deleteItemResult =>
      logger.info(s"Successfully deleted user $userId from ${touchpoint.dynamoAttributesTable}.")
      metrics.put("Delete", 1)
      deleteItemResult
    }.recover { case e: Throwable =>
      logger.warn(s"Failed to delete user $userId from ${touchpoint.dynamoAttributesTable}. Salesforce should retry.", e)
      Failure
    }
  }

  def updateMemberRecord(membershipUpdate: MembershipUpdate): Future[Object] = {

    def updateDynamo(attributes: Attributes) = {
      attributeService.set(attributes).map { putItemResult =>
        logger.info(s"Successfully inserted $attributes into ${touchpoint.dynamoAttributesTable}.")
        metrics.put("Update", 1)
        putItemResult
      }.recover { case e: Throwable =>
        logger.warn(s"Failed to insert $attributes into ${touchpoint.dynamoAttributesTable}. Salesforce should retry.", e)
        Failure
      }
    }

    val salesforceAttributes: Attributes = membershipUpdate.attributes

    logger.info(s"Salesforce called has been parsed. Request Id: $requestId. Attrs: $salesforceAttributes")

    (for {
      sfId <- OptionT(touchpoint.contactRepo.get(salesforceAttributes.UserId))
      membershipSubscription <- OptionT(touchpoint.subService.current[SubscriptionPlan.Member](sfId).map(_.headOption))
    } yield {

      // If the tier info does not match, we trust the info we get from Zuora, instead of the tier sent to us in the outbound message from Salesforce
      val tierFromZuora = membershipSubscription.plan.charges.benefit.id
      val tierFromSalesforce = salesforceAttributes.Tier
      if (tierFromZuora != tierFromSalesforce) logger.error(s"Differing tier info for $sfId : sf=$tierFromSalesforce zuora=$tierFromZuora")

      // If we have the card expiry date in Stripe, add them to Dynamo too.
      // TODO - refactor to use touchpoint.paymentService - requires membership-common model tweak first.
      val cardExpiryFromStripeF = (for {
        account <- OptionT(touchpoint.zuoraService.getAccount(membershipSubscription.accountId).map(Option(_)))
        paymentMethodId <- OptionT(Future.successful(account.defaultPaymentMethodId))
        paymentMethod <- OptionT(touchpoint.zuoraService.getPaymentMethod(paymentMethodId).map(Option(_)))
        customerToken <- OptionT(Future.successful(paymentMethod.secondTokenId))
        stripeCustomer <- OptionT(touchpoint.stripeService.Customer.read(customerToken).map(Option(_)))
      } yield {
        (stripeCustomer.card.exp_month, stripeCustomer.card.exp_year)
      }).run

      cardExpiryFromStripeF.map {
        case Some((expMonth, expYear)) => salesforceAttributes.copy(Tier = tierFromZuora, CardExpirationMonth = Some(expMonth), CardExpirationYear = Some(expYear))
        case None => salesforceAttributes.copy(Tier = tierFromZuora)
      }
    }).run.flatMap {
      case Some(zuoraAttributesF) => zuoraAttributesF.flatMap(updateDynamo)
      case None =>
        logger.error(s"Couldn't update $salesforceAttributes with information from Zuora")
        updateDynamo(salesforceAttributes)
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
        case membershipDelete: MembershipDeletion => deleteMemberRecord(membershipDelete)
        case membershipUpdate: MembershipUpdate => updateMemberRecord(membershipUpdate)
      }
      // Gather up the results of the futures and check for failures. Only send a success response to Salesforce if every processUpdate/processDeletion for the message succeeds
      Future.sequence(updates).map { updateSeq =>
        if (updateSeq.contains(Failure)) ApiErrors.internalError else ack
      }
  }
 }
}
