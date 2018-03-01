package services

import com.github.nscala_time.time.OrderingImplicits._
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.{GetCurrentPlans, Subscription}
import com.gu.memsub.{Benefit, Product}
import com.gu.zuora.rest.ZuoraRestService.{AccountSummary, PaymentMethodId, PaymentMethodResponse}
import loghandling.LoggingField.LogFieldString
import loghandling.LoggingWithLogstashFields
import models.{AccountWithSubscription, Attributes, DynamoAttributes, ZuoraAttributes}
import org.joda.time.{DateTime, LocalDate}

import scala.concurrent.{ExecutionContext, Future}
import scalaz.\/
import scalaz.syntax.std.boolean._
import services.PaymentFailureAlerter._

class AttributesMaker extends LoggingWithLogstashFields{

  def zuoraAttributes(
    identityId: String,
    subsWithAccounts: List[AccountWithSubscription],
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

    val maybeMembershipAndAccount = membershipSub flatMap { sub: Subscription[AnyPlan] => subsWithAccounts.find(subWithAccount => subWithAccount.subscription == Some(sub)) }

    val maybeAlertAvailable = maybeMembershipAndAccount flatMap { subAndAccount: AccountWithSubscription => subAndAccount.subscription map { sub: Subscription[AnyPlan] =>
        alertAvailableFor(subAndAccount.account, sub, paymentMethodGetter) map { alertAvailable: Boolean =>
          if(alertAvailable) Some("membership")
          else None
        }
      }
    }

    maybeAlertAvailable.getOrElse(Future.successful(None)) map { maybeAlert =>
      def customFields(identityId: String, alertAvailableFor: String) = List(LogFieldString("identity_id", identityId), LogFieldString("alert_available_for", alertAvailableFor))

      maybeAlert foreach { alert => logInfoWithCustomFields(s"User $identityId has an alert available: $alert", customFields(identityId, alert)) }

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
          //First we will just log if we have determined we have a membership alert for the user. Once we assess the logs,
          //we can start returning the value we've calculated
          //AlertAvailableFor = maybeAlert
          AlertAvailableFor = None
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
          AlertAvailableFor = zuora.AlertAvailableFor
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
          AlertAvailableFor = zuora.AlertAvailableFor
        ))
      case (None, _) => None
    }
  }
}

object AttributesMaker extends AttributesMaker