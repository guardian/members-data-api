package services

import com.gu.memsub.{Benefit, Product}
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.{GetCurrentPlans, Subscription}
import com.typesafe.scalalogging.LazyLogging
import models.Attributes
import org.joda.time.LocalDate
import com.github.nscala_time.time.OrderingImplicits.LocalDateOrdering

class AttributesMaker extends LazyLogging {

  def attributes(identityId: String, subs: List[Subscription[AnyPlan]], forDate: LocalDate): Option[Attributes] = {

    def getCurrentPlans(subscription: Subscription[AnyPlan]) = GetCurrentPlans(subscription, forDate).toOption
    def getTopProduct(subscription: Subscription[AnyPlan]) = getCurrentPlans(subscription).map(_.head.product)
    def getTopBenefitId(subscription: Subscription[AnyPlan]) = getCurrentPlans(subscription).map(_.head.charges.benefits.head.id)
    def getTopPlanName(subscription: Subscription[AnyPlan]) = getCurrentPlans(subscription).map(_.head.name)
    def getAllBenefits(subscription: Subscription[AnyPlan]) = getCurrentPlans(subscription).toList.flatMap(_.map(_.charges.benefits.list).list.flatten).toSet

    val groupedSubs: Map[Option[Product], List[Subscription[AnyPlan]]] = subs.groupBy(getTopProduct)

    val membershipSub = groupedSubs.getOrElse(Some(Product.Membership), Nil).headOption         // Assumes only 1 membership per Identity customer
    val contributionSub = groupedSubs.getOrElse(Some(Product.Contribution), Nil).headOption     // Assumes only 1 regular contribution per Identity customer
    val subsWhichIncludeDigitalPack = subs.filter(getAllBenefits(_).contains(Benefit.Digipack))

    val tier = membershipSub.flatMap(getTopBenefitId)
    val recurringContributionPaymentPlan = contributionSub.flatMap(getTopPlanName)
    val membershipJoinDate = membershipSub.map(_.startDate)
    val latestDigitalPackExpiryDate = subsWhichIncludeDigitalPack.map(_.termEndDate).sorted.reverse.headOption

    if(membershipSub.nonEmpty || contributionSub.nonEmpty || subsWhichIncludeDigitalPack.nonEmpty)
      Some(Attributes(
        UserId = identityId,
        Tier = tier,
        RecurringContributionPaymentPlan = recurringContributionPaymentPlan,
        MembershipJoinDate = membershipJoinDate,
        DigitalSubscriptionExpiryDate = latestDigitalPackExpiryDate
        )
      )
    else None
  }

}

object AttributesMaker extends AttributesMaker
