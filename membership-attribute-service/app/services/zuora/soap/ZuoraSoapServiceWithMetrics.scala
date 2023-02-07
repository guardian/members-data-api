package services.zuora.soap

import com.github.nscala_time.time.Imports
import com.gu.i18n.Currency
import _root_.models.subscription.Subscription
import _root_.models.subscription.Subscription.{Id, ProductRatePlanId}
import com.gu.salesforce.ContactId
import com.gu.stripe.Stripe
import com.gu.zuora.api.{InvoiceTemplate, PaymentGateway}
import services.zuora.soap.models.Commands.{Amend, Contribute, CreatePaymentMethod, Renew, Subscribe}
import services.zuora.soap.models.Queries.{PreviewInvoiceItem, Usage}
import services.zuora.soap.models.Results.{AmendResult, CreateResult, SubscribeResult, UpdateResult}
import services.zuora.soap.models.{PaymentSummary, Queries}
import monitoring.CreateMetrics
import org.joda.time.{LocalDate, ReadableDuration}

import scala.concurrent.{ExecutionContext, Future}

class ZuoraSoapServiceWithMetrics(wrapped: ZuoraSoapService, createMetrics: CreateMetrics)(implicit ec: ExecutionContext) extends ZuoraSoapService {
  private val metrics = createMetrics.forService(wrapped.getClass)

  override def lastPingTimeWithin(duration: ReadableDuration): Boolean = wrapped.lastPingTimeWithin(duration)

  override def getAccount(accountId: Subscription.AccountId): Future[Queries.Account] =
    metrics.measureDuration("getAccount")(wrapped.getAccount(accountId))

  override def getContact(billToId: String): Future[Queries.Contact] =
    metrics.measureDuration("getContact")(wrapped.getContact(billToId))

  override def getSubscription(id: Id): Future[Queries.Subscription] =
    metrics.measureDuration("getSubscription")(wrapped.getSubscription(id))

  override def previewInvoices(subscriptionId: Id, number: Int): Future[Seq[PreviewInvoiceItem]] =
    metrics.measureDuration("previewInvoices")(wrapped.previewInvoices(subscriptionId, number))

  override def previewInvoices(subscriptionId: String, contractAcceptanceDate: LocalDate, number: Int): Future[Seq[PreviewInvoiceItem]] =
    metrics.measureDuration("previewInvoicesWithDate")(wrapped.previewInvoices(subscriptionId, contractAcceptanceDate, number))

  override def previewInvoicesTillEndOfTerm(subscriptionId: Id): Future[Seq[PreviewInvoiceItem]] =
    metrics.measureDuration("previewInvoicesTillEndOfTerm")(wrapped.previewInvoicesTillEndOfTerm(subscriptionId))

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

  override def createPayPalPaymentMethod(accountId: Subscription.AccountId, payPalBaid: String, email: String): Future[UpdateResult] =
    metrics.measureDuration("createPayPalPaymentMethod")(wrapped.createPayPalPaymentMethod(accountId, payPalBaid, email))

  override def downgradePlan(
      subscription: Id,
      currentRatePlanId: Subscription.RatePlanId,
      futureRatePlanId: ProductRatePlanId,
      effectiveFrom: LocalDate,
  ): Future[AmendResult] =
    metrics.measureDuration("downgradePlan")(wrapped.downgradePlan(subscription, currentRatePlanId, futureRatePlanId, effectiveFrom))

  override def upgradeSubscription(amend: Amend): Future[AmendResult] =
    metrics.measureDuration("upgradeSubscription")(wrapped.upgradeSubscription(amend))

  override def renewSubscription(renew: Renew): Future[AmendResult] =
    metrics.measureDuration("renewSubscription")(wrapped.renewSubscription(renew))

  override def cancelPlan(subscriptionId: Id, ratePlan: Subscription.RatePlanId, cancelDate: LocalDate): Future[AmendResult] =
    metrics.measureDuration("cancelPlan")(wrapped.cancelPlan(subscriptionId, ratePlan, cancelDate))

  override def getPaymentSummary(subscriptionNumber: Subscription.Name, accountCurrency: Currency): Future[PaymentSummary] =
    metrics.measureDuration("getPaymentSummary")(wrapped.getPaymentSummary(subscriptionNumber, accountCurrency))

  override def getUsages(subscriptionNumber: Subscription.Name, unitOfMeasure: String, startDate: Imports.DateTime): Future[Seq[Usage]] =
    metrics.measureDuration("getUsages")(wrapped.getUsages(subscriptionNumber, unitOfMeasure, startDate))

  override def createFreeEventUsage(
      accountId: Subscription.AccountId,
      subscriptionNumber: Subscription.Name,
      description: String,
      quantity: Int,
  ): Future[CreateResult] =
    metrics.measureDuration("createFreeEventUsage")(wrapped.createFreeEventUsage(accountId, subscriptionNumber, description, quantity))

  override def getFeatures: Future[Seq[Queries.Feature]] =
    metrics.measureDuration("getFeatures")(wrapped.getFeatures)

  override def createSubscription(subscribe: Subscribe): Future[SubscribeResult] =
    metrics.measureDuration("createSubscription")(wrapped.createSubscription(subscribe))

  override def createContribution(contribute: Contribute): Future[SubscribeResult] =
    metrics.measureDuration("createContribution")(wrapped.createContribution(contribute))

  override def getPaymentMethod(id: String): Future[Queries.PaymentMethod] =
    metrics.measureDuration("getPaymentMethod")(wrapped.getPaymentMethod(id))

  override def updateActivationDate(subscriptionId: Id): Future[Unit] =
    metrics.measureDuration("updateActivationDate")(wrapped.updateActivationDate(subscriptionId))

  override def getAccounts(contactId: ContactId): Future[Seq[Queries.Account]] =
    metrics.measureDuration("getAccounts")(wrapped.getAccounts(contactId))

  override def getAccountIds(contactId: ContactId): Future[List[Subscription.AccountId]] =
    metrics.measureDuration("getAccountIds")(wrapped.getAccountIds(contactId))
}
