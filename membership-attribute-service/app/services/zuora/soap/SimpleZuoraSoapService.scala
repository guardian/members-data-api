package services.zuora.soap

import com.gu.i18n.{CountryGroup, Currency}
import com.gu.memsub.Subscription._
import com.gu.memsub.{Subscription => S}
import com.gu.monitoring.SafeLogging
import com.gu.salesforce.ContactId
import com.gu.stripe.Stripe
import com.gu.zuora.api.{InvoiceTemplate, PaymentGateway}
import com.gu.zuora.soap._
import com.gu.zuora.soap.actions.Actions._
import com.gu.zuora.soap.actions.{Action, XmlWriterAction}
import com.gu.zuora.soap.models.Commands._
import com.gu.zuora.soap.models.Queries.PreviewInvoiceItem
import com.gu.zuora.soap.models.Results.{AmendResult, CreateResult, UpdateResult}
import com.gu.zuora.soap.models.errors._
import com.gu.zuora.soap.models.{PaymentSummary, Queries => SoapQueries}
import com.gu.zuora.soap.writers.Command._
import org.joda.time.LocalDate

import scala.concurrent.{ExecutionContext, Future}

object SimpleZuoraSoapService {

  def latestInvoiceItems(items: Seq[SoapQueries.InvoiceItem]): Seq[SoapQueries.InvoiceItem] = {
    if (items.isEmpty)
      items
    else {
      val sortedItems = items.sortBy(_.chargeNumber)
      sortedItems.filter(_.subscriptionId == sortedItems.last.subscriptionId)
    }
  }
}

class SimpleZuoraSoapService(soapClient: ClientWithFeatureSupplier)(implicit ec: ExecutionContext) extends ZuoraSoapService with SafeLogging {

  import Readers._
  import SimpleZuoraSoapService._
  implicit private val sc = soapClient

  def getAccountIds(contactId: ContactId): Future[List[AccountId]] =
    soapClient
      .query[SoapQueries.Account](SimpleFilter("crmId", contactId.salesforceAccountId))
      .map(_.map(a => AccountId(a.id)).toList)

  override def getAccount(accountId: AccountId): Future[SoapQueries.Account] =
    soapClient.queryOne[SoapQueries.Account](SimpleFilter("id", accountId.get))

  override def getContact(contactId: String): Future[SoapQueries.Contact] =
    soapClient.queryOne[SoapQueries.Contact](SimpleFilter("Id", contactId))

  override def getSubscription(id: S.Id): Future[SoapQueries.Subscription] =
    soapClient.queryOne[SoapQueries.Subscription](SimpleFilter("id", id.get))

  private def previewInvoices(subscriptionId: String, paymentDate: LocalDate, action: (String, LocalDate) => Action[AmendResult]) = {
    val invoices = soapClient.authenticatedRequest(action(subscriptionId, paymentDate)).map(_.invoiceItems)
    invoices recover {
      case e: Error => Nil
      case e: Throwable => throw e
    }
  }

  override def previewInvoices(subscriptionId: S.Id, number: Int = 2): Future[Seq[PreviewInvoiceItem]] = {
    for {
      sub <- getSubscription(subscriptionId)
      previewInvoiceItems <- previewInvoices(sub.id, sub.contractAcceptanceDate, PreviewInvoicesViaAmend(number) _)
    } yield previewInvoiceItems
  }

  private def setDefaultPaymentMethod(
      accountId: AccountId,
      paymentMethodId: String,
      paymentGateway: PaymentGateway,
      invoiceTemplateOverride: Option[InvoiceTemplate],
  ) = {
    soapClient.authenticatedRequest(
      action = UpdateAccountPayment(
        accountId = accountId.get,
        defaultPaymentMethodId = SetTo(paymentMethodId),
        paymentGatewayName = paymentGateway.gatewayName,
        autoPay = Some(true),
        maybeInvoiceTemplateId = invoiceTemplateOverride.map(_.id),
      ),
    )
  }

  // When setting the payment gateway in the account we have to clear the default payment method to avoid conflicts
  private def setGatewayAndClearDefaultMethod(accountId: AccountId, paymentGateway: PaymentGateway) = {
    soapClient.authenticatedRequest(
      action = UpdateAccountPayment(
        accountId = accountId.get,
        defaultPaymentMethodId = Clear,
        paymentGatewayName = paymentGateway.gatewayName,
        autoPay = Some(false),
        maybeInvoiceTemplateId = None,
      ),
    )
  }

  // Creates a payment method in zuora and sets it as default in the specified account.To satisfy zuora validations this has to be done in three steps
  override def createPaymentMethod(command: CreatePaymentMethod): Future[UpdateResult] = for {
    _ <- setGatewayAndClearDefaultMethod(
      command.accountId,
      command.paymentGateway,
    ) // We need to set gateway correctly because it must match with the payment method we'll create below
    createMethodResult <- soapClient.extendedAuthenticatedRequest[CreateResult](new XmlWriterAction(command)(createPaymentMethodWrites))
    result <- setDefaultPaymentMethod(command.accountId, createMethodResult.id, command.paymentGateway, command.invoiceTemplateOverride)
  } yield result

  override def createCreditCardPaymentMethod(
      accountId: AccountId,
      stripeCustomer: Stripe.Customer,
      paymentGateway: PaymentGateway,
      invoiceTemplateOverride: Option[InvoiceTemplate],
  ): Future[UpdateResult] = {
    val card = stripeCustomer.card
    for {
      r <- setGatewayAndClearDefaultMethod(
        accountId,
        paymentGateway,
      ) // We need to set gateway correctly because it must match with the payment method
      paymentMethod <- soapClient.authenticatedRequest(
        CreateCreditCardReferencePaymentMethod(
          accountId = accountId.get,
          cardId = card.id,
          customerId = stripeCustomer.id,
          last4 = card.last4,
          cardCountry = CountryGroup.countryByCode(card.country),
          expirationMonth = card.exp_month,
          expirationYear = card.exp_year,
          cardType = card.`type`,
        ),
      )
      result <- setDefaultPaymentMethod(accountId, paymentMethod.id, paymentGateway, invoiceTemplateOverride)
    } yield result
  }

  override def getPaymentSummary(subscriptionNumber: S.Name, accountCurrency: Currency): Future[PaymentSummary] =
    for {
      invoiceItems <- soapClient.query[SoapQueries.InvoiceItem](SimpleFilter("SubscriptionNumber", subscriptionNumber.get))
    } yield {
      val filteredInvoices = latestInvoiceItems(invoiceItems)
      PaymentSummary(filteredInvoices, accountCurrency)
    }

  override def getPaymentMethod(id: String): Future[SoapQueries.PaymentMethod] =
    soapClient.queryOne[SoapQueries.PaymentMethod](SimpleFilter("Id", id))

}
