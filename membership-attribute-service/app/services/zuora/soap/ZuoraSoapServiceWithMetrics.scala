package services.zuora.soap

import com.github.nscala_time.time.Imports
import com.gu.i18n.Currency
import com.gu.memsub.Subscription
import com.gu.memsub.Subscription.{Id, ProductRatePlanId}
import com.gu.salesforce.ContactId
import com.gu.stripe.Stripe
import com.gu.zuora.api.{InvoiceTemplate, PaymentGateway}
import com.gu.zuora.soap.models.Commands.{Amend, Contribute, CreatePaymentMethod, Renew, Subscribe}
import com.gu.zuora.soap.models.Queries.{PreviewInvoiceItem, Usage}
import com.gu.zuora.soap.models.Results.{AmendResult, CreateResult, SubscribeResult, UpdateResult}
import com.gu.zuora.soap.models.{PaymentSummary, Queries}
import monitoring.CreateMetrics
import org.joda.time.{LocalDate, ReadableDuration}

import scala.concurrent.{ExecutionContext, Future}

class ZuoraSoapServiceWithMetrics(wrapped: ZuoraSoapService, createMetrics: CreateMetrics)(implicit ec: ExecutionContext) extends ZuoraSoapService {
  private val metrics = createMetrics.forService(wrapped.getClass)

  override def getAccount(accountId: Subscription.AccountId): Future[Queries.Account] =
    metrics.measureDuration("getAccount")(wrapped.getAccount(accountId))

  override def getContact(billToId: String): Future[Queries.Contact] =
    metrics.measureDuration("getContact")(wrapped.getContact(billToId))

  override def getSubscription(id: Id): Future[Queries.Subscription] =
    metrics.measureDuration("getSubscription")(wrapped.getSubscription(id))

  override def previewInvoices(subscriptionId: Id, number: Int): Future[Seq[PreviewInvoiceItem]] =
    metrics.measureDuration("previewInvoices")(wrapped.previewInvoices(subscriptionId, number))

  override def createPaymentMethod(request: CreatePaymentMethod): Future[UpdateResult] =
    metrics.measureDuration("createPaymentMethod")(wrapped.createPaymentMethod(request))

  override def createCreditCardPaymentMethod(
      accountId: Subscription.AccountId,
      stripeCustomer: Stripe.Customer,
      paymentGateway: PaymentGateway,
      invoiceTemplateOverride: Option[InvoiceTemplate],
  ): Future[UpdateResult] =
    metrics.measureDuration("createCreditCardPaymentMethod")(
      wrapped.createCreditCardPaymentMethod(accountId, stripeCustomer, paymentGateway, invoiceTemplateOverride),
    )

  override def getPaymentSummary(subscriptionNumber: Subscription.Name, accountCurrency: Currency): Future[PaymentSummary] =
    metrics.measureDuration("getPaymentSummary")(wrapped.getPaymentSummary(subscriptionNumber, accountCurrency))

  override def getPaymentMethod(id: String): Future[Queries.PaymentMethod] =
    metrics.measureDuration("getPaymentMethod")(wrapped.getPaymentMethod(id))

  override def getAccountIds(contactId: ContactId): Future[List[Subscription.AccountId]] =
    metrics.measureDuration("getAccountIds")(wrapped.getAccountIds(contactId))
}
