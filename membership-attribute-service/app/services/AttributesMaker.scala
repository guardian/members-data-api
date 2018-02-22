package services

import com.github.nscala_time.time.OrderingImplicits._
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.SubscriptionPlan.Member
import com.gu.memsub.subsv2.{GetCurrentPlans, Subscription, SubscriptionPlan}
import com.gu.memsub.{Benefit, PaymentMethod, Product}
import com.gu.zuora.rest.ZuoraRestService.{AccountSummary, PaymentMethodId, PaymentMethodResponse}
import com.typesafe.scalalogging.LazyLogging
import models.{Attributes, DynamoAttributes, ZuoraAttributes}
import org.joda.time.{DateTime, LocalDate}

import scala.concurrent.{ExecutionContext, Future}
import scalaz.{\/, \/-}
import scalaz.syntax.std.boolean._

class AttributesMaker extends LazyLogging {

  def zuoraAttributes(identityId: String, subs: List[Subscription[AnyPlan]], forDate: LocalDate): Option[ZuoraAttributes] = {

    def getCurrentPlans(subscription: Subscription[AnyPlan]): List[AnyPlan] = {
      GetCurrentPlans(subscription, forDate).map(_.list.toList).toList.flatten  // it's expected that users may not have any current plans
    }
    def getTopProduct(subscription: Subscription[AnyPlan]): Option[Product] = {
      getCurrentPlans(subscription).headOption.map(_.product)
    }
    def getTopPlanName(subscription: Subscription[AnyPlan]): Option[String] = {
      getCurrentPlans(subscription).headOption.map(_.name)
    }
    def getAllBenefits(subscription: Subscription[AnyPlan]): Set[Benefit] = {
      getCurrentPlans(subscription).flatMap(_.charges.benefits.list.toList).toSet
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
      ZuoraAttributes(
        UserId = identityId,
        Tier = tier,
        RecurringContributionPaymentPlan = recurringContributionPaymentPlan,
        MembershipJoinDate = membershipJoinDate,
        DigitalSubscriptionExpiryDate = latestDigitalPackExpiryDate
      )
    }
  }

  def zuoraAttributesWithAddedDynamoFields(zuoraAttributes: Option[ZuoraAttributes], dynamoAttributes: Option[DynamoAttributes]): Option[Attributes] = {
    (zuoraAttributes, dynamoAttributes) match {
      case (Some(zuora), Some(dynamo)) =>
        Some(Attributes(
          UserId = zuora.UserId,
          Tier = zuora.Tier,
          RecurringContributionPaymentPlan = zuora.RecurringContributionPaymentPlan,
          MembershipJoinDate = zuora.MembershipJoinDate,
          DigitalSubscriptionExpiryDate = zuora.DigitalSubscriptionExpiryDate,
          MembershipNumber = dynamo.MembershipNumber,
          AdFree = dynamo.AdFree
        ))
      case (Some(zuora), None) =>
        Some(Attributes(
          UserId = zuora.UserId,
          Tier = zuora.Tier,
          RecurringContributionPaymentPlan = zuora.RecurringContributionPaymentPlan,
          MembershipJoinDate = zuora.MembershipJoinDate,
          DigitalSubscriptionExpiryDate = zuora.DigitalSubscriptionExpiryDate,
          MembershipNumber = None,
          AdFree = None
        ))
      case (None, _) => None
    }
  }

  def actionAvailableFor(accountSummary: AccountSummary, subscription: Subscription[AnyPlan],
                         paymentMethodGetter: PaymentMethodId => Future[String \/ PaymentMethodResponse])(implicit ec: ExecutionContext): Future[Boolean] = {

    def creditCard(paymentMethodResponse: PaymentMethodResponse) = paymentMethodResponse.paymentMethodType == "CreditCardReferenceTransaction" || paymentMethodResponse.paymentMethodType == "CreditCard"

    val stillFreshInDays = 27
    def recentEnough(lastTransationDateTime: DateTime) = lastTransationDateTime.plusDays(stillFreshInDays).isAfterNow

    def actionForSub(accountSummary: AccountSummary, sub: Subscription[AnyPlan]): Future[String \/ Boolean] = {
      if(accountSummary.balance > 0 && accountSummary.defaultPaymentMethod.isDefined) {
        val eventualPaymentMethod: Future[\/[String, PaymentMethodResponse]] = paymentMethodGetter(accountSummary.defaultPaymentMethod.get.id)
        eventualPaymentMethod map { maybePaymentMethod: \/[String, PaymentMethodResponse] =>
          maybePaymentMethod.map { pm: PaymentMethodResponse =>
            if(creditCard(pm) && pm.numConsecutiveFailures > 0 && recentEnough(pm.lastTransactionDateTime))
              true
            else false
          }
        }
      }
      else Future.successful(\/.right(false))
    }

    actionForSub(accountSummary, subscription) map (_.getOrElse(false))
  }
}

object AttributesMaker extends AttributesMaker