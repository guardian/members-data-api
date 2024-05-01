package services.stripe

import com.gu.stripe.Stripe
import com.gu.stripe.Stripe.{Customer, CustomersPaymentMethods, StripeObject}

import scala.concurrent.Future

trait BasicStripeService {
  def fetchCustomer(customerId: String): Future[Customer]

  @Deprecated
  def createCustomer(card: String): Future[Customer]

  def createCustomerWithStripePaymentMethod(stripePaymentMethodID: String): Future[Customer]

  def fetchPaymentMethod(customerId: String): Future[CustomersPaymentMethods]

  def fetchSubscription(id: String): Future[Stripe.Subscription]
}
