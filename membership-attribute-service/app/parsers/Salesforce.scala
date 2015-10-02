package parsers

import models.MembershipAttributes

import scala.xml._
import scalaz.Scalaz._
import scalaz.\/

object Salesforce {
  type UserId = String

  sealed trait OutboundMessageChange
  case class MembershipUpdate(attributes: MembershipAttributes) extends OutboundMessageChange
  case class MembershipDeletion(userId: String) extends OutboundMessageChange

  sealed trait OutboundMessageParseError
  case class OrgIdMatchingError(organizationId: String) extends OutboundMessageParseError
  case class ParsingError(msg: String) extends OutboundMessageParseError

  /**
   * @param payload The outbound message content coming from Salesforce
   * @param organizationId  The id that qualifies the originating Salesforce account. This is to make sure that
   *                        the service do not process outbound messages from SF environments created by duplicating
   *                        Prod
   * @return
   */
  def parseOutboundMessage(payload: NodeSeq, organizationId: String): OutboundMessageParseError \/ OutboundMessageChange = {
    implicit class NodeSeqOps(ns: NodeSeq) {
      def getTag(tag: String): OutboundMessageParseError \/ Node =
        (ns \ tag).headOption \/> ParsingError(s"Error while parsing the outbound message: $tag not found.\n $payload")

      def getText(tag: String): OutboundMessageParseError \/ String = getTag(tag).map(_.text)
    }

    for {
      orgId <- (payload \\ "notifications").getText("OrganizationId")
      _ <- if (orgId === organizationId) ().right else OrgIdMatchingError(orgId).left
      obj <- (payload \\ "Notification").getTag("sObject")
      id <- obj.getText("IdentityID__c")
    } yield {
      val tier = (obj \ "Membership_Tier__c").map(_.text).headOption
      tier.fold[OutboundMessageChange](MembershipDeletion(id)) { t =>
        val num = (obj \ "Membership_Number__c").headOption.map(_.text)
        MembershipUpdate(MembershipAttributes(id, t, num))
      }
    }
  }
}
