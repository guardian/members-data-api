package services.zuora.soap

import com.gu.i18n.Currency
import com.gu.memsub.Subscription
import com.gu.memsub.Subscription.AccountId
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.salesforce.ContactId
import com.gu.stripe.Stripe
import com.gu.zuora.api.{InvoiceTemplate, PaymentGateway}
import com.gu.zuora.soap.models.Commands._
import com.gu.zuora.soap.models.Queries.PreviewInvoiceItem
import com.gu.zuora.soap.models.Results.UpdateResult
import com.gu.zuora.soap.models.{PaymentSummary, Queries => SoapQueries}

import scala.concurrent.Future

trait ZuoraSoapService {

  def getAccount(accountId: Subscription.AccountId)(implicit logPrefix: LogPrefix): Future[SoapQueries.Account]

  def getAccountIds(contactId: ContactId)(implicit logPrefix: LogPrefix): Future[List[AccountId]]

  def getContact(billToId: String)(implicit logPrefix: LogPrefix): Future[SoapQueries.Contact]

  def getSubscription(id: Subscription.Id)(implicit logPrefix: LogPrefix): Future[SoapQueries.Subscription]

  def previewInvoices(subscriptionId: Subscription.Id, number: Int = 2)(implicit logPrefix: LogPrefix): Future[Seq[PreviewInvoiceItem]]

  def createPaymentMethod(request: CreatePaymentMethod)(implicit logPrefix: LogPrefix): Future[UpdateResult]

  def createCreditCardPaymentMethod(
      accountId: Subscription.AccountId,
      stripeCustomer: Stripe.Customer,
      paymentGateway: PaymentGateway,
      invoiceTemplateOverride: Option[InvoiceTemplate],
  )(implicit logPrefix: LogPrefix): Future[UpdateResult]

  def getPaymentSummary(subscriptionNumber: Subscription.Name, accountCurrency: Currency)(implicit logPrefix: LogPrefix): Future[PaymentSummary]

  def getPaymentMethod(id: String)(implicit logPrefix: LogPrefix): Future[SoapQueries.PaymentMethod]

}
