package services

import com.github.nscala_time.time.OrderingImplicits._
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.{GetCurrentPlans, Subscription}
import com.gu.memsub.{Benefit, Product}
import com.gu.zuora.rest.ZuoraRestService
import com.gu.zuora.rest.ZuoraRestService.{PaymentMethodId, PaymentMethodResponse}
import loghandling.LoggingField.LogFieldString
import loghandling.LoggingWithLogstashFields
import models.{AccountWithSubscriptions, ZuoraAttributes}
import org.joda.time.LocalDate
import PaymentFailureAlerter.alertAvailableFor

import scala.concurrent.{ExecutionContext, Future}
import scalaz.\/
import scalaz.syntax.std.boolean._
import services.AttributesMaker.{getCurrentPlans, getSubsWhichIncludeDigitalPack}

case class ProductData(product:Product, account: ZuoraRestService.AccountObject, subscription:Subscription[AnyPlan])

class AttributesMaker extends LoggingWithLogstashFields{


  def zuoraAttributes(
                       identityId: String,
                       subsWithAccounts: List[AccountWithSubscriptions],
                       paymentMethodGetter: PaymentMethodId => Future[\/[String, PaymentMethodResponse]],
                       forDate: LocalDate)(implicit ec: ExecutionContext): Future[Option[ZuoraAttributes]] = {

    val subs: List[Subscription[AnyPlan]] = subsWithAccounts.flatMap(subAccount => subAccount.subscriptions)

    def getTopProduct(subscription: Subscription[AnyPlan]): Option[Product] = {
      getCurrentPlans(subscription, forDate).headOption.map(_.product)
    }
    def getTopPlanName(subscription: Subscription[AnyPlan]): Option[String] = {
      getCurrentPlans(subscription, forDate).headOption.map(_.name)
    }

    val groupedSubs: Map[Option[Product], List[Subscription[AnyPlan]]] = subs.groupBy(getTopProduct)

    val sortedProductData: List[ProductData] = {

      val accountWithSub = subsWithAccounts.collect {
        case AccountWithSubscriptions(account, subscriptions) if subscriptions.nonEmpty =>
          subscriptions.map(sub => (account, sub))
      }.flatten

      val groupedByProduct = accountWithSub.groupBy {
        case (account, subscription) => getTopProduct(subscription)
      }

      val firstSubPerProduct = groupedByProduct.collect {
        case (Some(k), sub :: _) => (k, sub)
      }

      firstSubPerProduct.map {
        case (product, (account, subscription)) => ProductData(product, account, subscription)
      }.toList.sortWith(_.product.name < _.product.name)
    }

    //TODO this should really return a list of all products with alerts and the banner logic etc should handle multiple
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
    val subsWhichIncludeDigitalPack = getSubsWhichIncludeDigitalPack(subs, forDate)

    val paperSubscriptions = groupedSubs.filterKeys{
      case Some(_: Product.Weekly) => false // guardian weekly extends Paper, so we need to explicitly filter that out
      case Some(_: Product.Paper) => true
      case _ => false
    }.values.flatten

    val guardianWeeklySubscriptions = groupedSubs.filterKeys{
      case Some(_: Product.Weekly) => true // guardian weekly extends Paper, so we need to explicitly filter that out
      case _ => false
    }.values.flatten

    val hasAttributableProduct: Boolean = membershipSub.nonEmpty ||
      contributionSub.nonEmpty ||
      subsWhichIncludeDigitalPack.nonEmpty ||
      paperSubscriptions.nonEmpty ||
      guardianWeeklySubscriptions.nonEmpty

    findFirstAlert(sortedProductData) map { maybeAlert =>
      def customFields(identityId: String, alertAvailableFor: String) = List(LogFieldString("identity_id", identityId), LogFieldString("alert_available_for", alertAvailableFor))

      maybeAlert foreach { alert => logInfoWithCustomFields(s"User $identityId has an alert available: $alert", customFields(identityId, alert)) }

      hasAttributableProduct.option {
        val tier: Option[String] = membershipSub.flatMap(getCurrentPlans(_, forDate).headOption.map(_.charges.benefits.head.id))
        val recurringContributionPaymentPlan: Option[String] = contributionSub.flatMap(getTopPlanName)
        val membershipJoinDate: Option[LocalDate] = membershipSub.map(_.startDate)
        val latestDigitalPackExpiryDate: Option[LocalDate] = Some(subsWhichIncludeDigitalPack.map(_.termEndDate)).filter(_.nonEmpty).map(_.max)
        val latestPaperExpiryDate: Option[LocalDate] = Some(paperSubscriptions.map(_.termEndDate)).filter(_.nonEmpty).map(_.max)
        val latestGuardianWeeklyExpiryDate: Option[LocalDate] = Some(guardianWeeklySubscriptions.map(_.termEndDate)).filter(_.nonEmpty).map(_.max)
        ZuoraAttributes(
          UserId = identityId,
          Tier = tier,
          RecurringContributionPaymentPlan = recurringContributionPaymentPlan,
          MembershipJoinDate = membershipJoinDate,
          DigitalSubscriptionExpiryDate = latestDigitalPackExpiryDate,
          PaperSubscriptionExpiryDate = latestPaperExpiryDate,
          GuardianWeeklySubscriptionExpiryDate = latestGuardianWeeklyExpiryDate,
          AlertAvailableFor = maybeAlert
        )
      }
    }
  }
}

object AttributesMaker extends AttributesMaker {
  def getCurrentPlans(subscription: Subscription[AnyPlan], forDate: LocalDate): List[AnyPlan] =
    GetCurrentPlans(subscription, forDate).map(_.list.toList).toList.flatten  // it's expected that users may not have any current plans

  def getAllBenefits(subscription: Subscription[AnyPlan], forDate: LocalDate): Set[Benefit] =
    getCurrentPlans(subscription, forDate).flatMap(_.charges.benefits.list.toList).toSet

  def getSubsWhichIncludeDigitalPack(subs: List[Subscription[AnyPlan]], forDate: LocalDate) =
    subs.filter(getAllBenefits(_, forDate).contains(Benefit.Digipack))
}
