package services.zuora.payment

import com.gu.memsub.Subscription.{AccountId, Id}
import com.gu.memsub._
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.services.model.PaymentDetails
import com.gu.zuora.soap.models.Queries.Account
import scalaz.\/

import scala.concurrent.Future

trait PaymentService {
  def getPaymentMethod(accountId: AccountId, defaultMandateIdIfApplicable: Option[String] = None): Future[Option[PaymentMethod]]
  def getPaymentCard(accountId: AccountId): Future[Option[PaymentCard]]
  def paymentDetails(
      sub: Subscription[SubscriptionPlan.Free] \/ Subscription[SubscriptionPlan.Paid],
      defaultMandateIdIfApplicable: Option[String] = None,
  ): Future[PaymentDetails]
  def billingSchedule(subscription: Id, number: Int = 2): Future[Option[BillingSchedule]]
  def billingSchedule(subId: Id, accountId: AccountId, numberOfBills: Int): Future[Option[BillingSchedule]]
  def billingSchedule(subId: Id, account: Account, numberOfBills: Int): Future[Option[BillingSchedule]]
}
