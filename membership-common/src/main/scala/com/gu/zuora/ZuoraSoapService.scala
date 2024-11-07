package com.gu.zuora

import com.gu.i18n.{CountryGroup, Currency}
import com.gu.memsub.Subscription._
import com.gu.memsub.{Subscription => S}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.gu.salesforce.ContactId
import com.gu.stripe.Stripe
import com.gu.zuora.api.{PaymentGateway}
import com.gu.zuora.soap.Readers._
import com.gu.zuora.soap._
import com.gu.zuora.soap.actions.{Action, XmlWriterAction}
import com.gu.zuora.soap.actions.Actions._
import com.gu.zuora.soap.models.Commands.CreatePaymentMethod
import com.gu.zuora.soap.models.Queries.PreviewInvoiceItem
import com.gu.zuora.soap.models.Results.{AmendResult, CreateResult, UpdateResult}
import com.gu.zuora.soap.models.errors._
import com.gu.zuora.soap.models.{PaymentSummary, Queries => SoapQueries}
import com.gu.zuora.soap.writers.Command.createPaymentMethodWrites
import org.joda.time.LocalDate

import scala.concurrent.{ExecutionContext, Future}

object ZuoraSoapService {

  def latestInvoiceItems(items: Seq[SoapQueries.InvoiceItem]): Seq[SoapQueries.InvoiceItem] = {
    if (items.isEmpty)
      items
    else {
      val sortedItems = items.sortBy(_.chargeNumber)
      sortedItems.filter(_.subscriptionId == sortedItems.last.subscriptionId)
    }
  }
}

trait SoapClient[M[_]] {

  def getAccountIds(contactId: ContactId)(implicit logPrefix: LogPrefix): M[List[AccountId]]
}

class ZuoraSoapService(soapClient: soap.Client)(implicit ec: ExecutionContext) extends SoapClient[Future] with SafeLogging {

  import ZuoraSoapService._

  def getAccountIds(contactId: ContactId)(implicit logPrefix: LogPrefix): Future[List[AccountId]] =
    soapClient
      .query[SoapQueries.Account](SimpleFilter("crmId", contactId.salesforceAccountId))
      .map(_.map(a => AccountId(a.id)).toList)

  def getAccount(accountId: AccountId)(implicit logPrefix: LogPrefix): Future[SoapQueries.Account] =
    soapClient.queryOne[SoapQueries.Account](SimpleFilter("id", accountId.get))

  def getContact(contactId: String)(implicit logPrefix: LogPrefix): Future[SoapQueries.Contact] =
    soapClient.queryOne[SoapQueries.Contact](SimpleFilter("Id", contactId))

  def getSubscription(id: S.Id)(implicit logPrefix: LogPrefix): Future[SoapQueries.Subscription] =
    soapClient.queryOne[SoapQueries.Subscription](SimpleFilter("id", id.get))

  private def previewInvoices(subscriptionId: String, paymentDate: LocalDate, action: (String, LocalDate) => Action[AmendResult])(implicit
      logPrefix: LogPrefix,
  ) = {
    val invoices = soapClient.authenticatedRequest(action(subscriptionId, paymentDate)).map(_.invoiceItems)
    invoices recover {
      case e: Error => Nil
      case e: Throwable => throw e
    }
  }

  def previewInvoices(subscriptionId: S.Id, number: Int = 2)(implicit logPrefix: LogPrefix): Future[Seq[PreviewInvoiceItem]] = {
    for {
      sub <- getSubscription(subscriptionId)
      previewInvoiceItems <- previewInvoices(sub.id, sub.contractAcceptanceDate, PreviewInvoicesViaAmend(number) _)
    } yield previewInvoiceItems
  }

  private def setDefaultPaymentMethod(
      accountId: AccountId,
      paymentMethodId: String,
      paymentGateway: PaymentGateway,
  )(implicit logPrefix: LogPrefix) = {
    soapClient.authenticatedRequest(
      action = UpdateAccountPayment(
        accountId = accountId.get,
        defaultPaymentMethodId = SetTo(paymentMethodId),
        paymentGatewayName = paymentGateway.gatewayName,
        autoPay = Some(true),
      ),
    )
  }

  // When setting the payment gateway in the account we have to clear the default payment method to avoid conflicts
  private def setGatewayAndClearDefaultMethod(accountId: AccountId, paymentGateway: PaymentGateway)(implicit logPrefix: LogPrefix) = {
    soapClient.authenticatedRequest(
      action = UpdateAccountPayment(
        accountId = accountId.get,
        defaultPaymentMethodId = Clear,
        paymentGatewayName = paymentGateway.gatewayName,
        autoPay = Some(false),
      ),
    )
  }

  // Creates a payment method in zuora and sets it as default in the specified account.To satisfy zuora validations this has to be done in three steps
  def createPaymentMethod(command: CreatePaymentMethod)(implicit logPrefix: LogPrefix): Future[UpdateResult] = for {
    _ <- setGatewayAndClearDefaultMethod(
      command.accountId,
      command.paymentGateway,
    ) // We need to set gateway correctly because it must match with the payment method we'll create below
    createMethodResult <- soapClient.authenticatedRequest[CreateResult](new XmlWriterAction(command)(createPaymentMethodWrites))
    result <- setDefaultPaymentMethod(command.accountId, createMethodResult.id, command.paymentGateway)
  } yield result

  def createCreditCardPaymentMethod(
      accountId: AccountId,
      stripeCustomer: Stripe.Customer,
      paymentGateway: PaymentGateway,
  )(implicit logPrefix: LogPrefix): Future[UpdateResult] = {
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
      result <- setDefaultPaymentMethod(accountId, paymentMethod.id, paymentGateway)
    } yield result
  }

  def getPaymentSummary(subscriptionNumber: S.SubscriptionNumber, accountCurrency: Currency)(implicit logPrefix: LogPrefix): Future[PaymentSummary] =
    for {
      invoiceItems <- soapClient.query[SoapQueries.InvoiceItem](SimpleFilter("SubscriptionNumber", subscriptionNumber.getNumber))
    } yield {
      val filteredInvoices = latestInvoiceItems(invoiceItems)
      PaymentSummary(filteredInvoices, accountCurrency)
    }

  def getPaymentMethod(id: String)(implicit logPrefix: LogPrefix): Future[SoapQueries.PaymentMethod] =
    soapClient.queryOne[SoapQueries.PaymentMethod](SimpleFilter("Id", id))

}
