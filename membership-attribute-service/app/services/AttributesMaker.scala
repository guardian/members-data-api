package services

import com.gu.memsub.Product
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.{GetCurrentPlans, Subscription}
import com.typesafe.scalalogging.LazyLogging
import models.Attributes
import org.joda.time.LocalDate

class AttributesMaker extends LazyLogging {

  def attributes(userId: String, subs: List[Subscription[AnyPlan]], forDate: LocalDate): Option[Attributes] = {

    val groupedSubs: Map[Option[Product], List[Subscription[AnyPlan]]] = subs.groupBy(subscription => GetCurrentPlans(subscription, forDate).toOption.map(_.head.product))
    val membershipSub = groupedSubs.getOrElse(Some(Product.Membership), Nil)
    val contributionSub = groupedSubs.getOrElse(Some(Product.Contribution), Nil)

    val tier = membershipSub.headOption.map(sub => GetCurrentPlans(sub, forDate).toOption.map(_.head.charges.benefits.head.id)).flatten
    val recurringContributionPaymentPlan = contributionSub.headOption.map(sub => GetCurrentPlans(sub, forDate).toOption.map(_.head.name)).flatten
    val membershipJoinDate = membershipSub.map(_.startDate).headOption

    if(!membershipSub.isEmpty || !contributionSub.isEmpty)
      Some(Attributes(
        UserId = userId,
        Tier = tier,
        RecurringContributionPaymentPlan = recurringContributionPaymentPlan,
        MembershipJoinDate = membershipJoinDate
        )
      )
    else None
  }

}

object AttributesMaker extends AttributesMaker
