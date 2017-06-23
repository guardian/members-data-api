package controllers

import actions.BackendFromSalesforceAction
import com.gu.memsub.Membership
import com.gu.memsub.subsv2.SubscriptionPlan
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.typesafe.scalalogging.LazyLogging
import models.{ApiErrors, Attributes, CardDetails, Wallet}
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

    object ContextLogging {
      case class RequestId(value: Int)
      implicit val requestId = RequestId(scala.util.Random.nextInt)
      def info(message: String)(implicit requestId: RequestId) =
        logger.info(s"$requestId: $message")
      def warn(message: String)(implicit requestId: RequestId) =
        logger.warn(s"$requestId: $message")
      def warn(message: String, e: Throwable)(implicit requestId: RequestId) =
        logger.warn(s"$requestId: $message", e)
      def error(message: String)(implicit requestId: RequestId) =
        logger.error(s"$requestId: $message")

      implicit class FutureContextLoggable[T](future: Future[T]) {
        def logWith(message: String, extractor: T => Any = identity): Future[T] = {
          future.foreach(any => info(s"$message {${extractor(any)}}"))
          future
        }

      }
      implicit class ContextLoggable[T](t: T) {
        def logWith(message: String): T = {
          t match {
            case future: Future[_] => future.foreach(any => info(s"$message {$any}"))
            case any => info(s"$message {$any}")
          }
          t
        }

      }

    }
    import ContextLogging._

    implicit val pf = Membership

    info(s"Called from Saleforce to modify the Members Data API")

    def deleteMemberRecord(membershipDeletion: MembershipDeletion): Future[Object] = {
      val userId = membershipDeletion.userId
      attributeService.delete(userId).map { deleteItemResult =>
        info(s"Successfully deleted user $userId from ${touchpoint.dynamoAttributesTable}.")
        metrics.put("Delete", 1)
        deleteItemResult
      }.recover { case e: Throwable =>
        warn(s"Failed to delete user $userId from ${touchpoint.dynamoAttributesTable}. Salesforce should retry.", e)
        Failure
      }
    }

    def updateMemberRecord(membershipUpdate: MembershipUpdate): Future[Object] = {

      def updateDynamo(attributes: Attributes) = {
        attributeService.update(attributes).map { putItemResult =>
          info(s"Successfully inserted $attributes into ${touchpoint.dynamoAttributesTable}.")
          metrics.put("Update", 1)
          putItemResult
        }.recover { case e: Throwable =>
          warn(s"Failed to insert $attributes into ${touchpoint.dynamoAttributesTable}. Salesforce should retry.", e)
          Failure
        }
      }

      info(s"Salesforce called has been parsed. Attrs: $membershipUpdate")

      (for {
        sfId <- OptionT(touchpoint.contactRepo.get(membershipUpdate.UserId).logWith("contact id from SF", _.map(_.salesforceContactId)))
        membershipSubscription <- OptionT(touchpoint.subService.current[SubscriptionPlan.Member](sfId).logWith("current subscriptions", _.map(_.id)).map(_.headOption))
      } yield {

        // Zuora is the master for product info, so we use the tier from Zuora regardless of what Salesforce sends
        val tierFromZuora = membershipSubscription.plan.charges.benefit.id

        // If we have the card expiry date in Stripe, add them to Dynamo too inside a Wallet construct.
        // TODO - refactor to use touchpoint.paymentService - requires membership-common model tweak first.
        val walletF = for {
          account <- OptionT(touchpoint.zuoraService.getAccount(membershipSubscription.accountId).map(Option(_)))
          paymentMethodId <- OptionT(Future.successful(account.defaultPaymentMethodId))
          paymentMethod <- OptionT(touchpoint.zuoraService.getPaymentMethod(paymentMethodId).map(Option(_)))
          customerToken <- OptionT(Future.successful(paymentMethod.secondTokenId))
          stripeCustomer <- OptionT(touchpoint.stripeService.Customer.read(customerToken).map(Option(_)))
        } yield {
          Wallet(membershipCard = Some(CardDetails.fromStripeCard(stripeCustomer.card, Membership.id)))
        }

        val membershipJoinDate = membershipSubscription.startDate // acceptanceDate is the date of first payment, but we want to know the signup date - contract effective date

        walletF.run.map { maybeWallet =>
          Attributes(
            UserId = membershipUpdate.UserId,
            Tier = Some(tierFromZuora),
            MembershipNumber = membershipUpdate.MembershipNumber,
            AdFree = None,
            Wallet = maybeWallet,
            MembershipJoinDate = Some(membershipJoinDate)
          )
        }
      }).run.flatMap {
        case Some(zuoraAttributesF) =>
          zuoraAttributesF.onSuccess{ case attr =>
            info(s"ready to update dynamo with $attr")
          }
          zuoraAttributesF.flatMap(updateDynamo)
        case None =>
          error(s"Couldn't update $membershipUpdate with information from Zuora")
          Future.successful(Failure)
      }
    }

    SFParser.parseOutboundMessage(request.body, validOrgId) match {
      case -\/(ParsingError(msg)) =>
        error(s"Could not parse payload. \n$msg")
        Future(ApiErrors.badRequest(msg))
      case -\/(OrgIdMatchingError(orgId)) =>
        error(s"Wrong organization Id: $orgId")
        Future(ApiErrors.unauthorized.copy("Wrong organization Id"))
      // On successful parse, we should end up with a Seq of Salesforce objects to update
      case \/-(outboundMessageChanges @ Seq(_*)) =>
        info(s"Parsed Salesforce message successfully. Salesforce sent ${outboundMessageChanges.length} objects to update: $outboundMessageChanges")
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
