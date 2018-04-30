package services

import com.github.nscala_time.time.OrderingImplicits._
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.{GetCurrentPlans, Subscription}
import com.gu.memsub.{Benefit, Product}
import com.gu.zuora.rest.ZuoraRestService
import com.gu.zuora.rest.ZuoraRestService.{PaymentMethodId, PaymentMethodResponse}
import loghandling.LoggingField.LogFieldString
import loghandling.LoggingWithLogstashFields
import models.{AccountWithSubscription, Attributes, DynamoAttributes, ZuoraAttributes}
import org.joda.time.LocalDate
import PaymentFailureAlerter.alertAvailableFor
import scala.concurrent.{ExecutionContext, Future}
import scalaz.\/
import scalaz.syntax.std.boolean._

case class ProductData(product:Product, account: ZuoraRestService.AccountObject, subscription:Subscription[AnyPlan])

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

    val sortedProductData: List[ProductData] = {
      val accountsWithNonEmptySubs = subsWithAccounts.collect {
        case AccountWithSubscription(account, Some(subscription)) => (account, subscription)
      }

      val groupedByProduct = accountsWithNonEmptySubs.groupBy {
        case (account, subscription) => getTopProduct(subscription)
      }

      val firstSubPerProduct = groupedByProduct.collect {
        case (Some(k), sub :: _) => (k, sub)
      }

      firstSubPerProduct.map {
        case (product, (account, subscription)) => ProductData(product, account, subscription)
      }.toList.sortWith(_.product.name < _.product.name)
    }

    def findFirstAlert(productData: List[ProductData]): Future[Option[String]] = productData match {

      case Nil => Future.successful(None)

      case headProductData :: productDataTail => {
        alertAvailableFor(headProductData.account, headProductData.subscription, paymentMethodGetter).flatMap { alertAvailable =>
          if (alertAvailable) {
            Future.successful(Some(headProductData.product.name))
          } else {
            findFirstAlert(productDataTail)
          }
        }
      }
    }

    val membershipSub = groupedSubs.getOrElse(Some(Product.Membership), Nil).headOption         // Assumes only 1 membership per Identity customer
    val contributionSub = groupedSubs.getOrElse(Some(Product.Contribution), Nil).headOption     // Assumes only 1 regular contribution per Identity customer
    val subsWhichIncludeDigitalPack = subs.filter(getAllBenefits(_).contains(Benefit.Digipack))

    val hasAttributableProduct = membershipSub.nonEmpty || contributionSub.nonEmpty || subsWhichIncludeDigitalPack.nonEmpty

    val maybeMembershipAndAccount = membershipSub flatMap { sub: Subscription[AnyPlan] => subsWithAccounts.find(_.subscription.contains(sub)) }

    findFirstAlert(sortedProductData) map { maybeAlert =>
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
          AlertAvailableFor = maybeAlert
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