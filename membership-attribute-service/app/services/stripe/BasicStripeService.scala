package services.stripe

import com.gu.i18n.Currency
import services.stripe.Stripe.{Charge, Customer, CustomersPaymentMethods, StripeObject}

import scala.concurrent.Future

trait BasicStripeService {
  def fetchCustomer(customerId: String): Future[Customer]

  @Deprecated
  def createCustomer(card: String): Future[Customer]

  def createCustomerWithStripePaymentMethod(stripePaymentMethodID: String): Future[Customer]

  def fetchPaymentMethod(customerId: String): Future[CustomersPaymentMethods]

  def createCharge(amount: Int, currency: Currency, email: String, description: String, cardToken: String, meta: Map[String, String]): Future[Charge]

  def fetchBalanceTransaction(id: String): Future[Stripe.BalanceTransaction]

  def fetchEvent(id: String): Future[Stripe.Event[StripeObject]]

  def fetchCharge(id: String): Future[Option[Stripe.Event[Charge]]]

  def fetchSubscription(id: String): Future[Stripe.Subscription]
}
