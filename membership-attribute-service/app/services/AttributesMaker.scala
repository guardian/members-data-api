package services

import com.github.nscala_time.time.OrderingImplicits._
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.{GetCurrentPlans, Subscription}
import com.gu.memsub.{Benefit, Product}
import com.typesafe.scalalogging.LazyLogging
import models.Attributes
import org.joda.time.LocalDate

import scalaz.syntax.std.boolean._

class AttributesMaker extends LazyLogging {

  def attributes(identityId: String, subs: List[Subscription[AnyPlan]], forDate: LocalDate): Option[Attributes] = {

    def getCurrentPlans(subscription: Subscription[AnyPlan]): List[AnyPlan] = {
      GetCurrentPlans(subscription, forDate).map(_.list).toList.flatten  // it's expected that users may not have any current plans
    }
    def getTopProduct(subscription: Subscription[AnyPlan]): Option[Product] = {
      getCurrentPlans(subscription).headOption.map(_.product)
    }
    def getTopPlanName(subscription: Subscription[AnyPlan]): Option[String] = {
      getCurrentPlans(subscription).headOption.map(_.name)
    }
    def getAllBenefits(subscription: Subscription[AnyPlan]): Set[Benefit] = {
      getCurrentPlans(subscription).flatMap(_.charges.benefits.list).toSet
    }

    val groupedSubs: Map[Option[Product], List[Subscription[AnyPlan]]] = subs.groupBy(getTopProduct)

    val membershipSub = groupedSubs.getOrElse(Some(Product.Membership), Nil).headOption         // Assumes only 1 membership per Identity customer
    val contributionSub = groupedSubs.getOrElse(Some(Product.Contribution), Nil).headOption     // Assumes only 1 regular contribution per Identity customer
    val subsWhichIncludeDigitalPack = subs.filter(getAllBenefits(_).contains(Benefit.Digipack))

    val hasAttributableProduct = membershipSub.nonEmpty || contributionSub.nonEmpty || subsWhichIncludeDigitalPack.nonEmpty

    hasAttributableProduct.option {
      val tier: Option[String] = membershipSub.flatMap(getCurrentPlans(_).headOption.map(_.charges.benefits.head.id))
      val recurringContributionPaymentPlan: Option[String] = contributionSub.flatMap(getTopPlanName)
      val membershipJoinDate: Option[LocalDate] = membershipSub.map(_.startDate)
      val latestDigitalPackExpiryDate: Option[LocalDate] = Some(subsWhichIncludeDigitalPack.map(_.termEndDate)).filter(_.nonEmpty).map(_.max)
      Attributes(
        UserId = identityId,
        Tier = tier,
        RecurringContributionPaymentPlan = recurringContributionPaymentPlan,
        MembershipJoinDate = membershipJoinDate,
        DigitalSubscriptionExpiryDate = latestDigitalPackExpiryDate
      )
    }
  }

}

object AttributesMaker extends AttributesMaker
