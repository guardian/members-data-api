package services.zuora.soap

import com.github.nscala_time.time.Imports._
import com.gu.i18n.Currency
import _root_.models.subscription.Subscription
import _root_.models.subscription.Subscription.{AccountId, Id, ProductRatePlanId}
import services.salesforce.model.ContactId
import com.gu.stripe.Stripe
import com.gu.zuora.api.{InvoiceTemplate, PaymentGateway}
import services.zuora.soap.models.Queries.{PreviewInvoiceItem, Usage}
import services.zuora.soap.models.Results.{AmendResult, CreateResult, SubscribeResult, UpdateResult}
import services.zuora.soap.models.{PaymentSummary, Queries => SoapQueries}
import org.joda.time.{LocalDate, ReadableDuration}
import services.zuora.soap.models.Commands.{Amend, Contribute, CreatePaymentMethod, Renew, Subscribe}

import scala.concurrent.Future

trait ZuoraSoapService {

  def lastPingTimeWithin(duration: ReadableDuration): Boolean

  def getAccount(accountId: Subscription.AccountId): Future[SoapQueries.Account]

  def getAccounts(contactId: ContactId): Future[Seq[SoapQueries.Account]]

  def getAccountIds(contactId: ContactId): Future[List[AccountId]]

  def getContact(billToId: String): Future[SoapQueries.Contact]

  def getSubscription(id: Subscription.Id): Future[SoapQueries.Subscription]

  def previewInvoices(subscriptionId: Subscription.Id, number: Int = 2): Future[Seq[PreviewInvoiceItem]]

  def previewInvoices(subscriptionId: String, contractAcceptanceDate: LocalDate, number: Int): Future[Seq[PreviewInvoiceItem]]

  def previewInvoicesTillEndOfTerm(subscriptionId: Subscription.Id): Future[Seq[PreviewInvoiceItem]]

  def createPaymentMethod(request: CreatePaymentMethod): Future[UpdateResult]

  def createCreditCardPaymentMethod(
      accountId: Subscription.AccountId,
      stripeCustomer: Stripe.Customer,
      paymentGateway: PaymentGateway,
      invoiceTemplateOverride: Option[InvoiceTemplate],
  ): Future[UpdateResult]

  def createPayPalPaymentMethod(accountId: Subscription.AccountId, payPalBaid: String, email: String): Future[UpdateResult]

  def downgradePlan(
      subscription: Subscription.Id,
      currentRatePlanId: Subscription.RatePlanId,
      futureRatePlanId: ProductRatePlanId,
      effectiveFrom: LocalDate,
  ): Future[AmendResult]

  def upgradeSubscription(amend: Amend): Future[AmendResult]

  def renewSubscription(renew: Renew): Future[AmendResult]

  def cancelPlan(subscriptionId: Subscription.Id, ratePlan: Subscription.RatePlanId, cancelDate: LocalDate): Future[AmendResult]

  def getPaymentSummary(subscriptionNumber: Subscription.Name, accountCurrency: Currency): Future[PaymentSummary]

  def getUsages(subscriptionNumber: Subscription.Name, unitOfMeasure: String, startDate: DateTime): Future[Seq[Usage]]

  def createFreeEventUsage(
      accountId: Subscription.AccountId,
      subscriptionNumber: Subscription.Name,
      description: String,
      quantity: Int,
  ): Future[CreateResult]

  def getFeatures: Future[Seq[SoapQueries.Feature]]

  def createSubscription(subscribe: Subscribe): Future[SubscribeResult]

  def createContribution(contribute: Contribute): Future[SubscribeResult]

  def getPaymentMethod(id: String): Future[SoapQueries.PaymentMethod]

  def updateActivationDate(subscriptionId: Id): Future[Unit]
}
