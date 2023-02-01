package models.subscription.services.api

import models.subscription.Subscription.{AccountId, Id}
import models.subscription.{BillingSchedule, _}
import models.subscription.subsv2.{Subscription, SubscriptionPlan}
import models.PaymentDetails
import com.gu.stripe.StripeService
import _root_.services.zuora.soap.models.Queries.Account

import scala.concurrent.Future
import scalaz.\/

trait PaymentService {
  def getPaymentMethod(accountId: AccountId, defaultMandateIdIfApplicable: Option[String] = None): Future[Option[PaymentMethod]]
  def getPaymentCard(accountId: AccountId): Future[Option[PaymentCard]]
  @Deprecated def setPaymentCardWithStripeToken(
      accountId: AccountId,
      stripeToken: String,
      stripeService: StripeService,
  ): Future[Option[PaymentCardUpdateResult]]
  def setPaymentCardWithStripePaymentMethod(
      accountId: AccountId,
      stripePaymentMethodID: String,
      stripeService: StripeService,
  ): Future[Option[PaymentCardUpdateResult]]
  def paymentDetails(
      sub: Subscription[SubscriptionPlan.Free] \/ Subscription[SubscriptionPlan.Paid],
      defaultMandateIdIfApplicable: Option[String] = None,
  ): Future[PaymentDetails]
  def billingSchedule(subscription: Id, number: Int = 2): Future[Option[BillingSchedule]]
  def billingSchedule(subId: Id, accountId: AccountId, numberOfBills: Int): Future[Option[BillingSchedule]]
  def billingSchedule(subId: Id, account: Account, numberOfBills: Int): Future[Option[BillingSchedule]]
}
