package services.stripe

import com.gu.i18n.Currency
import services.stripe.Stripe
import services.stripe.Stripe.{Charge, Customer, CustomersPaymentMethods, StripeObject}
import monitoring.CreateMetrics

import scala.concurrent.{ExecutionContext, Future}

class BasicStripeServiceWithMetrics(wrapped: BasicStripeService, createMetrics: CreateMetrics)(implicit ec: ExecutionContext)
    extends BasicStripeService {
  private val metrics = createMetrics.forService(wrapped.getClass)

  override def fetchCustomer(customerId: String): Future[Customer] =
    metrics.measureDuration("fetchCustomer")(wrapped.fetchCustomer(customerId))

  override def createCustomer(card: String): Future[Customer] =
    metrics.measureDuration("createCustomer")(wrapped.createCustomer(card))

  override def createCustomerWithStripePaymentMethod(stripePaymentMethodID: String): Future[Customer] =
    metrics.measureDuration("createCustomerWithStripePaymentMethod")(wrapped.createCustomerWithStripePaymentMethod(stripePaymentMethodID))

  override def fetchPaymentMethod(customerId: String): Future[CustomersPaymentMethods] =
    metrics.measureDuration("fetchPaymentMethod")(wrapped.fetchPaymentMethod(customerId))

  override def createCharge(
      amount: Int,
      currency: Currency,
      email: String,
      description: String,
      cardToken: String,
      meta: Map[String, String],
  ): Future[Charge] =
    metrics.measureDuration("createCharge")(wrapped.createCharge(amount, currency, email, description, cardToken, meta))

  override def fetchBalanceTransaction(id: String): Future[Stripe.BalanceTransaction] =
    metrics.measureDuration("fetchBalanceTransaction")(wrapped.fetchBalanceTransaction(id))

  override def fetchEvent(id: String): Future[Stripe.Event[StripeObject]] =
    metrics.measureDuration("fetchEvent")(wrapped.fetchEvent(id))

  override def fetchCharge(id: String): Future[Option[Stripe.Event[Charge]]] =
    metrics.measureDuration("fetchCharge")(wrapped.fetchCharge(id))

  override def fetchSubscription(id: String): Future[Stripe.Subscription] =
    metrics.measureDuration("fetchSubscription")(wrapped.fetchSubscription(id))
}
