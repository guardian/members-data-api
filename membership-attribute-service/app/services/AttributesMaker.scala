package services

import com.gu.zuora.ZuoraRestService.RestSubscriptions
import com.typesafe.scalalogging.LazyLogging
import models.Attributes
import org.joda.time.LocalDate

class AttributesMaker extends LazyLogging {

  private case class BasicSubscriptionInfo(productName: String, ratePlanName: String, contractEffectiveDate: String)

  def attributes(userId: String, subs: List[RestSubscriptions]): Option[Attributes] = {
    logger.info(s"SUBS SUBS SUBS: $subs")

    val filteredSubs = subscriptionInfo(subs)
    val membershipSub = filteredSubs.filter(sub => isMember(sub.productName))
    val contributionSub = filteredSubs.filter(sub => isContributor(sub.productName))

    val tier = if(membershipSub.nonEmpty) Some(membershipSub.head.productName) else None
    val membershipJoinDate = if(membershipSub.nonEmpty) Some(new LocalDate(membershipSub.head.contractEffectiveDate)) else None
    val recurringContributionPaymentPlan = if(contributionSub.nonEmpty) Some(contributionSub.head.ratePlanName) else None

    if(filteredSubs.nonEmpty)
      Some(Attributes(
        UserId = userId,
        Tier = tier,
        RecurringContributionPaymentPlan = recurringContributionPaymentPlan,
        MembershipJoinDate = membershipJoinDate
        )
      )
    else None
  }

  private def isMember(productName: String): Boolean = List("Supporter", "Partner", "Patron").contains(productName)
  private def isContributor(productName: String): Boolean = "Contributor" == productName
  private def isMemberOrContributor(productName: String) = isContributor(productName) || isMember(productName)

  private def subscriptionInfo(subs: List[RestSubscriptions]): Seq[BasicSubscriptionInfo] = {

    val flatSubs: Seq[(String, String, String)] = subs.flatMap { sub => sub.subscriptions}
      .map { restSub => (restSub.ratePlans.head.productName, restSub.ratePlans.head.ratePlanName, restSub.contractEffectiveDate)}

    //todo surely this is convoluted
    flatSubs.map(s => BasicSubscriptionInfo(s._1, s._2, s._3)).filter(subInfo => isMemberOrContributor(subInfo.productName))
  }
}

object AttributesMaker extends AttributesMaker
