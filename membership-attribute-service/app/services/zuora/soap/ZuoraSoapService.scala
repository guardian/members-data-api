package services.zuora.soap

import com.gu.i18n.Currency
import com.gu.memsub.Subscription
import com.gu.memsub.Subscription.AccountId
import com.gu.salesforce.ContactId
import com.gu.stripe.Stripe
import com.gu.zuora.api.{InvoiceTemplate, PaymentGateway}
import com.gu.zuora.soap.models.Commands._
import com.gu.zuora.soap.models.Queries.PreviewInvoiceItem
import com.gu.zuora.soap.models.Results.UpdateResult
import com.gu.zuora.soap.models.{PaymentSummary, Queries => SoapQueries}

import scala.concurrent.Future

trait ZuoraSoapService {

  def getAccount(accountId: Subscription.AccountId): Future[SoapQueries.Account]

  def getAccountIds(contactId: ContactId): Future[List[AccountId]]

  def getContact(billToId: String): Future[SoapQueries.Contact]

  def getSubscription(id: Subscription.Id): Future[SoapQueries.Subscription]

  def previewInvoices(subscriptionId: Subscription.Id, number: Int = 2): Future[Seq[PreviewInvoiceItem]]

  def createPaymentMethod(request: CreatePaymentMethod): Future[UpdateResult]

  def createCreditCardPaymentMethod(
      accountId: Subscription.AccountId,
      stripeCustomer: Stripe.Customer,
      paymentGateway: PaymentGateway,
      invoiceTemplateOverride: Option[InvoiceTemplate],
  ): Future[UpdateResult]

  def getPaymentSummary(subscriptionNumber: Subscription.Name, accountCurrency: Currency): Future[PaymentSummary]

  def getPaymentMethod(id: String): Future[SoapQueries.PaymentMethod]

}
