package com.gu.memsub.subsv2

import com.github.nscala_time.time.Imports._
import com.gu.memsub
import com.gu.memsub.BillingPeriod.OneTimeChargeBillingPeriod
import com.gu.memsub.Product.Contribution
import com.gu.memsub.promo.PromoCode
import com.gu.memsub.subsv2.SubscriptionPlan._
import com.gu.memsub.subsv2.services.Sequence
import com.gu.memsub.Product
import org.joda.time.{DateTime, LocalDate}

import scalaz.syntax.all._
import scalaz.{NonEmptyList, Validation, \/}

case class CovariantNonEmptyList[+T](head: T, tail: List[T]) {
  val list = head :: tail
}

case class Subscription(
    id: memsub.Subscription.Id,
    name: memsub.Subscription.Name,
    accountId: memsub.Subscription.AccountId,
    startDate: LocalDate,
    acceptanceDate: LocalDate,
    termStartDate: LocalDate,
    termEndDate: LocalDate,
    casActivationDate: Option[DateTime],
    promoCode: Option[PromoCode],
    isCancelled: Boolean,
    hasPendingFreePlan: Boolean,
    plans: CovariantNonEmptyList[SubscriptionPlan],
    readerType: ReaderType,
    gifteeIdentityId: Option[String],
    autoRenew: Boolean,
) {

  val firstPaymentDate: LocalDate = (acceptanceDate :: plans.list.map(_.start)).min

  lazy val plan: SubscriptionPlan = {
    GetCurrentPlans(this, LocalDate.now).fold(error => throw new RuntimeException(error), _.head)
  }

}

/*
this goes through all the plan objects and only returns the ones that are current.
This could include more than one of the same type especially if it's the changeover date.
It then sorts them so the "best" one is first in the list.  Best just means more expensive,
so this code could be an area of disaster in future.
If we are comparing two Free plans (possible because there is a legacy Friend plan in Zuora) we need to work
with the newest plan for upgrade and cancel scenarios, so in this case the most recent start date wins.
 */
object GetCurrentPlans {

  /*- negative if x < y
   *  - positive if x > y
   *  - zero otherwise (if x == y)*/
  private val planGoodnessOrder = new scala.Ordering[SubscriptionPlan] {
    override def compare(planX: SubscriptionPlan, planY: SubscriptionPlan): Int = {
      val priceX = planX.charges.price.prices.head.amount
      val priceY = planY.charges.price.prices.head.amount
      (priceX * 100).toInt - (priceY * 100).toInt
    }
  }

  def bestCancelledPlan(sub: Subscription): Option[SubscriptionPlan] =
    if (sub.isCancelled && sub.termEndDate.isBefore(LocalDate.now()))
      sub.plans.list.sorted(planGoodnessOrder).reverse.headOption
    else None

  case class DiscardedPlan(plan: SubscriptionPlan, why: String)

  def apply(sub: Subscription, date: LocalDate): String \/ NonEmptyList[SubscriptionPlan] = {

    val currentPlans = sub.plans.list.toList.sorted(planGoodnessOrder).reverse.map { plan =>
      // If the sub hasn't been paid yet but has started we should fast-forward to the date of first payment (free trial)
      val dateToCheck = if (sub.startDate <= date && sub.acceptanceDate > date) sub.acceptanceDate else date

      val unvalidated = Validation.s[NonEmptyList[DiscardedPlan]](plan)
      /*
      Note that a Contributor may have future sub.acceptanceDate and plan.startDate values if the user has
      updated their payment amount via MMA since starting the contribution. In this case the alreadyStarted assessment
      just checks that the sub.startDate is before, or the same as, the date received by this function.
       */
      val ensureStarted = unvalidated.ensure(DiscardedPlan(plan, s"hasn't started as of $dateToCheck").wrapNel)(_)
      val alreadyStarted =
        if (plan.product == Contribution)
          ensureStarted(_ => sub.startDate <= date)
        else
          ensureStarted(_.start <= dateToCheck)
      val contributorPlanCancelled =
        alreadyStarted.ensure(DiscardedPlan(plan, "has a contributor plan which has been cancelled or removed").wrapNel)(_)
      val paidPlanEnded = alreadyStarted.ensure(DiscardedPlan(plan, "has a paid plan which has ended").wrapNel)(_)
      val digipackGiftEnded = alreadyStarted.ensure(DiscardedPlan(plan, "has a digipack gift plan which has ended").wrapNel)(_)
      if (plan.product == Product.Contribution)
        contributorPlanCancelled(_ => !sub.isCancelled && !plan.lastChangeType.contains("Remove"))
      else if (plan.product == Product.Digipack && plan.charges.billingPeriod == OneTimeChargeBillingPeriod)
        digipackGiftEnded(_ => sub.termEndDate >= dateToCheck)
      else
        paidPlanEnded(_ => plan.end >= dateToCheck)
    }

    Sequence(
      currentPlans.map(
        _.leftMap(_.map(discard => s"Discarded ${discard.plan.id.get} because it ${discard.why}").list.toList.mkString("\n")).disjunction,
      ),
    )
  }
}

object Subscription {
  def partial(hasPendingFreePlan: Boolean)(
      id: memsub.Subscription.Id,
      name: memsub.Subscription.Name,
      accountId: memsub.Subscription.AccountId,
      startDate: LocalDate,
      acceptanceDate: LocalDate,
      termStartDate: LocalDate,
      termEndDate: LocalDate,
      casActivationDate: Option[DateTime],
      promoCode: Option[PromoCode],
      isCancelled: Boolean,
      readerType: ReaderType,
      gifteeIdentityId: Option[String],
      autoRenew: Boolean,
  )(plans: NonEmptyList[SubscriptionPlan]): Subscription =
    new Subscription(
      id = id,
      name = name,
      accountId = accountId,
      startDate = startDate,
      acceptanceDate = acceptanceDate,
      termStartDate = termStartDate,
      termEndDate = termEndDate,
      casActivationDate = casActivationDate,
      promoCode = promoCode,
      isCancelled = isCancelled,
      hasPendingFreePlan = hasPendingFreePlan,
      plans = CovariantNonEmptyList(plans.head, plans.tail.toList),
      readerType = readerType,
      gifteeIdentityId = gifteeIdentityId,
      autoRenew = autoRenew,
    )
}

object ReaderType {

  case object Direct extends ReaderType {
    val value = "Direct"
  }
  case object Gift extends ReaderType {
    val value = "Gift"
  }
  case object Agent extends ReaderType {
    val value = "Agent"
  }
  case object Student extends ReaderType {
    val value = "Student"
  }
  case object Complementary extends ReaderType {
    val value = "Complementary" // Spelled this way to match value in Saleforce/Zuora
    val alternateSpelling = "Complimentary"
  }
  case object Corporate extends ReaderType {
    val value = "Corporate"
  }
  case object Patron extends ReaderType {
    val value = "Patron"
  }

  def apply(maybeString: Option[String]): ReaderType =
    maybeString
      .map {
        case Direct.value => Direct
        case Gift.value => Gift
        case Agent.value => Agent
        case Student.value => Student
        case Complementary.value => Complementary
        case Complementary.alternateSpelling => Complementary
        case Corporate.value => Corporate
        case Patron.value => Patron
        case unknown => throw new RuntimeException(s"Unknown reader type: $unknown")
      }
      .getOrElse(Direct)

}
sealed trait ReaderType {
  def value: String
}
