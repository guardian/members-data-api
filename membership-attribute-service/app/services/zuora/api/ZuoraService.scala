package services.zuora.api

import com.github.nscala_time.time.Imports._
import com.gu.i18n.Currency
import com.gu.memsub.Subscription.{Id, ProductRatePlanId}
import com.gu.memsub.{Subscription => S}
import services.stripe.Stripe
import com.gu.zuora.soap.models.Commands._
import com.gu.zuora.soap.models.Queries.{PreviewInvoiceItem, Usage}
import com.gu.zuora.soap.models.Results.{AmendResult, CreateResult, SubscribeResult, UpdateResult}
import com.gu.zuora.soap.models.{PaymentSummary, Queries => SoapQueries}
import org.joda.time.{LocalDate, ReadableDuration}

import scala.concurrent.Future

trait ZuoraService {

  def lastPingTimeWithin(d: ReadableDuration): Boolean

  def getAccount(accountId: S.AccountId): Future[SoapQueries.Account]

  def getContact(billToId: String): Future[SoapQueries.Contact]

  def getSubscription(id: S.Id): Future[SoapQueries.Subscription]

  def previewInvoices(subscriptionId: S.Id, number: Int = 2): Future[Seq[PreviewInvoiceItem]]

  def previewInvoices(subscriptionId: String, contractAcceptanceDate: LocalDate, number: Int): Future[Seq[PreviewInvoiceItem]]

  def previewInvoicesTillEndOfTerm(subscriptionId: S.Id): Future[Seq[PreviewInvoiceItem]]

  def createPaymentMethod(request: CreatePaymentMethod): Future[UpdateResult]

  def createCreditCardPaymentMethod(
      accountId: S.AccountId,
      stripeCustomer: Stripe.Customer,
      paymentGateway: PaymentGateway,
      invoiceTemplateOverride: Option[InvoiceTemplate],
  ): Future[UpdateResult]

  def createPayPalPaymentMethod(accountId: S.AccountId, payPalBaid: String, email: String): Future[UpdateResult]

  def downgradePlan(
      subscription: S.Id,
      currentRatePlanId: S.RatePlanId,
      futureRatePlanId: ProductRatePlanId,
      effectiveFrom: LocalDate,
  ): Future[AmendResult]

  def upgradeSubscription(u: Amend): Future[AmendResult]

  def renewSubscription(u: Renew): Future[AmendResult]

  def cancelPlan(subscriptionId: S.Id, ratePlan: S.RatePlanId, cancelDate: LocalDate): Future[AmendResult]

  def getPaymentSummary(subscriptionNumber: S.Name, accountCurrency: Currency): Future[PaymentSummary]

  def getUsages(subscriptionNumber: S.Name, unitOfMeasure: String, startDate: DateTime): Future[Seq[Usage]]

  def createFreeEventUsage(accountId: S.AccountId, subscriptionNumber: S.Name, description: String, quantity: Int): Future[CreateResult]

  def getFeatures: Future[Seq[SoapQueries.Feature]]

  def createSubscription(sub: Subscribe): Future[SubscribeResult]

  def createContribution(con: Contribute): Future[SubscribeResult]

  def getPaymentMethod(id: String): Future[SoapQueries.PaymentMethod]

  def updateActivationDate(subscriptionId: Id): Future[Unit]
}
