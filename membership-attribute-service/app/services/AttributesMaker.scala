package services

import com.github.nscala_time.time.OrderingImplicits._
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.{GetCurrentPlans, Subscription}
import com.gu.memsub.{Benefit, Product}
import com.gu.monitoring.SafeLogger
import com.gu.zuora.rest.ZuoraRestService.{AccountSummary, PaymentMethodId, PaymentMethodResponse}
import models.{Attributes, CustomerAccount, DynamoAttributes, ZuoraAttributes}
import org.joda.time.{DateTime, LocalDate}

import scala.concurrent.{ExecutionContext, Future}
import scalaz.{EitherT, \/, \/-}
import scalaz.syntax.std.boolean._

class AttributesMaker {

  def zuoraAttributes(
    identityId: String,
    subsWithAccounts: List[CustomerAccount],
    paymentMethodGetter: PaymentMethodId => Future[\/[String, PaymentMethodResponse]],
    forDate: LocalDate)(implicit ec: ExecutionContext): Future[Option[ZuoraAttributes]] = {

    val subs: List[Subscription[AnyPlan]] = subsWithAccounts.flatMap(subAccount => subAccount.subscription)

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

    val maybeMembershipSubAndAccount = membershipSub flatMap { sub: Subscription[AnyPlan] => subsWithAccounts.find(subWithAccount => subWithAccount.subscription == Some(sub)) }

    val maybeActionAvailable = maybeMembershipSubAndAccount flatMap { subAndAccount: CustomerAccount => subAndAccount.subscription map { sub: Subscription[AnyPlan] =>
        actionAvailableFor(subAndAccount.account, sub, paymentMethodGetter) map { actionAvailable: Boolean =>
          if(actionAvailable) Some("membership")
          else None
        }
      }
    }

    maybeActionAvailable.getOrElse(Future.successful(None)) map { maybeAction =>
      maybeAction map { action => SafeLogger.info(s"User $identityId has an action available: $action") }

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
          DigitalSubscriptionExpiryDate = latestDigitalPackExpiryDate,
          //First we will just log if we have determined we have a membership action for the user. Once we assess the logs,
          //we can start returning the value we've calculated
          //ActionAvailableFor = maybeAction
          ActionAvailableFor = None
        )
      }
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
          AdFree = dynamo.AdFree,
          ActionAvailableFor = zuora.ActionAvailableFor
        ))
      case (Some(zuora), None) =>
        Some(Attributes(
          UserId = zuora.UserId,
          Tier = zuora.Tier,
          RecurringContributionPaymentPlan = zuora.RecurringContributionPaymentPlan,
          MembershipJoinDate = zuora.MembershipJoinDate,
          DigitalSubscriptionExpiryDate = zuora.DigitalSubscriptionExpiryDate,
          MembershipNumber = None,
          AdFree = None,
          ActionAvailableFor = zuora.ActionAvailableFor
        ))
      case (None, _) => None
    }
  }

  def actionAvailableFor(
    accountSummary: AccountSummary, subscription: Subscription[AnyPlan],
    paymentMethodGetter: PaymentMethodId => Future[String \/ PaymentMethodResponse]
    )(implicit ec: ExecutionContext): Future[Boolean] = {

    def creditCard(paymentMethodResponse: PaymentMethodResponse) = paymentMethodResponse.paymentMethodType == "CreditCardReferenceTransaction" || paymentMethodResponse.paymentMethodType == "CreditCard"

    val stillFreshInDays = 27
    def recentEnough(lastTransationDateTime: DateTime) = lastTransationDateTime.plusDays(stillFreshInDays).isAfterNow

    val userInPaymentFailure: Future[\/[String, Boolean]] = {
      if(accountSummary.balance > 0 && accountSummary.defaultPaymentMethod.isDefined) {
        val eventualPaymentMethod: Future[\/[String, PaymentMethodResponse]] = paymentMethodGetter(accountSummary.defaultPaymentMethod.get.id)

        eventualPaymentMethod map { maybePaymentMethod: \/[String, PaymentMethodResponse] =>
          maybePaymentMethod.map { pm: PaymentMethodResponse =>
            creditCard(pm) &&
              pm.numConsecutiveFailures > 0 &&
              recentEnough(pm.lastTransactionDateTime)
          }
        }
      }
      else Future.successful(\/.right(false))
    }

    userInPaymentFailure map (_.getOrElse(false))
  }
}

object AttributesMaker extends AttributesMaker