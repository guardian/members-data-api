package services.stripe

import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.stripe.Stripe
import com.gu.stripe.Stripe.{Customer, CustomersPaymentMethods, StripeObject}

import scala.concurrent.Future

trait BasicStripeService {
  def fetchCustomer(customerId: String)(implicit logPrefix: LogPrefix): Future[Customer]

  @Deprecated
  def createCustomer(card: String)(implicit logPrefix: LogPrefix): Future[Customer]

  def createCustomerWithStripePaymentMethod(stripePaymentMethodID: String)(implicit logPrefix: LogPrefix): Future[Customer]

  def fetchPaymentMethod(customerId: String)(implicit logPrefix: LogPrefix): Future[CustomersPaymentMethods]

  def fetchSubscription(id: String)(implicit logPrefix: LogPrefix): Future[Stripe.Subscription]
}
