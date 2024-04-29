package services.zuora.soap

import com.gu.i18n.Currency
import com.gu.memsub.Subscription
import com.gu.memsub.Subscription.Id
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.salesforce.ContactId
import com.gu.stripe.Stripe
import com.gu.zuora.api.{InvoiceTemplate, PaymentGateway}
import com.gu.zuora.soap.models.Commands.CreatePaymentMethod
import com.gu.zuora.soap.models.Queries.PreviewInvoiceItem
import com.gu.zuora.soap.models.Results.UpdateResult
import com.gu.zuora.soap.models.{PaymentSummary, Queries}
import monitoring.CreateMetrics

import scala.concurrent.{ExecutionContext, Future}

class ZuoraSoapServiceWithMetrics(wrapped: ZuoraSoapService, createMetrics: CreateMetrics)(implicit ec: ExecutionContext) extends ZuoraSoapService {
  private val metrics = createMetrics.forService(wrapped.getClass)

  override def getAccount(accountId: Subscription.AccountId)(implicit logPrefix: LogPrefix): Future[Queries.Account] =
    metrics.measureDuration("getAccount")(wrapped.getAccount(accountId))

  override def getContact(billToId: String)(implicit logPrefix: LogPrefix): Future[Queries.Contact] =
    metrics.measureDuration("getContact")(wrapped.getContact(billToId))

  override def getSubscription(id: Id)(implicit logPrefix: LogPrefix): Future[Queries.Subscription] =
    metrics.measureDuration("getSubscription")(wrapped.getSubscription(id))

  override def previewInvoices(subscriptionId: Id, number: Int)(implicit logPrefix: LogPrefix): Future[Seq[PreviewInvoiceItem]] =
    metrics.measureDuration("previewInvoices")(wrapped.previewInvoices(subscriptionId, number))

  override def createPaymentMethod(request: CreatePaymentMethod)(implicit logPrefix: LogPrefix): Future[UpdateResult] =
    metrics.measureDuration("createPaymentMethod")(wrapped.createPaymentMethod(request))

  override def createCreditCardPaymentMethod(
      accountId: Subscription.AccountId,
      stripeCustomer: Stripe.Customer,
      paymentGateway: PaymentGateway,
      invoiceTemplateOverride: Option[InvoiceTemplate],
  )(implicit logPrefix: LogPrefix): Future[UpdateResult] =
    metrics.measureDuration("createCreditCardPaymentMethod")(
      wrapped.createCreditCardPaymentMethod(accountId, stripeCustomer, paymentGateway, invoiceTemplateOverride),
    )

  override def getPaymentSummary(subscriptionNumber: Subscription.Name, accountCurrency: Currency)(implicit
      logPrefix: LogPrefix,
  ): Future[PaymentSummary] =
    metrics.measureDuration("getPaymentSummary")(wrapped.getPaymentSummary(subscriptionNumber, accountCurrency))

  override def getPaymentMethod(id: String)(implicit logPrefix: LogPrefix): Future[Queries.PaymentMethod] =
    metrics.measureDuration("getPaymentMethod")(wrapped.getPaymentMethod(id))

  override def getAccountIds(contactId: ContactId)(implicit logPrefix: LogPrefix): Future[List[Subscription.AccountId]] =
    metrics.measureDuration("getAccountIds")(wrapped.getAccountIds(contactId))
}
