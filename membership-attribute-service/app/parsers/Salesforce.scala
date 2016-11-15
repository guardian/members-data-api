package parsers

import models.Attributes
import scala.xml.{Node, _}
import scalaz.Scalaz._
import scalaz.{-\/, \/, \/-}

object Salesforce {
  type UserId = String

  sealed trait OutboundMessageChange

  case class MembershipUpdate(attributes: Attributes) extends OutboundMessageChange

  case class MembershipDeletion(userId: String) extends OutboundMessageChange

  sealed trait OutboundMessageParseError

  case class OrgIdMatchingError(organizationId: String) extends OutboundMessageParseError

  case class ParsingError(msg: String) extends OutboundMessageParseError

  /**
    * @param payload        The outbound message content coming from Salesforce
    * @param organizationId The id that qualifies the originating Salesforce account. This is to make sure that
    *                       the service do not process outbound messages from SF environments created by duplicating
    *                       Prod
    * @return
    */
  def parseOutboundMessage(payload: NodeSeq, organizationId: String): OutboundMessageParseError \/ Seq[OutboundMessageChange] = {
    implicit class NodeSeqOps(ns: NodeSeq) {
      def getTag(tag: String): OutboundMessageParseError \/ Node =
        (ns \ tag).headOption \/> ParsingError(s"Error while parsing the outbound message: $tag not found.\n $payload")

      def getText(tag: String): OutboundMessageParseError \/ String = getTag(tag).map(_.text)
    }

    def orgIdValid: OutboundMessageParseError \/ Unit = {
      (payload \\ "notifications").getText("OrganizationId").flatMap(orgId => if (orgId != organizationId) OrgIdMatchingError(orgId).left else ().right)
    }

    def getSalesforceObjects: OutboundMessageParseError \/ NodeSeq = {
        val salesforceObjects = (payload \\ "sObject")
        if (salesforceObjects.isEmpty) {
          ParsingError(s"No Salesforce Objects were found when parsing the message. \n $payload").left
        }
        else salesforceObjects.right
    }

    def processSalesforceObject(salesforceObject: Node): OutboundMessageChange = {
      val id = (salesforceObject \ "IdentityID__c").map(_.text).head
      val tier = (salesforceObject \ "Membership_Tier__c").map(_.text).headOption
      val num = (salesforceObject \ "Membership_Number__c").headOption.map(_.text)
      // Match on the Tier to determine the required action
      tier match {
        // If Salesforce Contact object has no Tier, we assume the user has expired/cancelled and mark them for deletion
        case None => MembershipDeletion(id)
        // If the Salesforce Contact has a Tier, we mark them for an update
        case Some(tier) => MembershipUpdate(Attributes(id, tier, num))
      }
    }

    orgIdValid match {
      // If we can validate the organization ID, then we get a Right and can attempt to process the notifications.
      case \/-(_) => getSalesforceObjects.map { _.map(sfObject => processSalesforceObject(sfObject)) }
        // Otherwise we pass on the error
      case error @ -\/(_) => error
    }
  }
}
