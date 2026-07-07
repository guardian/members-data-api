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
import com.gu.zuora.soap.models.Results.{CreateResult, UpdateResult}
import com.gu.zuora.soap.models.errors._
import com.gu.zuora.soap.models.{PaymentSummary, Queries => SoapQueries}
import com.gu.zuora.soap.writers.Command.createPaymentMethodWrites
import com.gu.zuora.rest.ZuoraQueryReads._
import play.api.libs.json.Reads

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

class ZuoraSoapService(soapClient: soap.Client, restClient: rest.SimpleClient[Future])(implicit ec: ExecutionContext)
    extends SoapClient[Future]
    with SafeLogging {

  import ZuoraSoapService._

  /* These reads used to go through the SOAP query API; they now hit the equivalent Zuora REST endpoints (object/{type}/{id} and action/query),
     mapping the responses back to the same case classes so callers are unchanged. getObject fails the Future if the object is missing or the call
     errors, like the old queryOne; query returns the records (empty if none) and only fails on a REST error, like the old plural query. */
  private def getObject[A: Reads](url: String)(implicit logPrefix: LogPrefix): Future[A] =
    restClient.get[A](url).map(_.valueOr(error => throw QueryError(s"Zuora REST get '$url' failed: $error")))

  private def query[A: Reads](zoql: String)(implicit logPrefix: LogPrefix): Future[List[A]] =
    restClient
      .post[RestQuery, QueryResponse[A]]("action/query", RestQuery(zoql))
      .map(_.valueOr(error => throw QueryError(s"Zuora REST query '$zoql' failed: $error")).records)

  def getAccountIds(contactId: ContactId)(implicit logPrefix: LogPrefix): Future[List[AccountId]] =
    query[AccountIdRecord](s"select Id from account where crmId = '${contactId.salesforceAccountId}'")
      .map(_.map(record => AccountId(record.id)))

  def getAccount(accountId: AccountId)(implicit logPrefix: LogPrefix): Future[SoapQueries.Account] =
    getObject[SoapQueries.Account](s"object/account/${accountId.get}")

  def getContact(contactId: String)(implicit logPrefix: LogPrefix): Future[SoapQueries.Contact] =
    getObject[SoapQueries.Contact](s"object/contact/$contactId")

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
    query[SoapQueries.InvoiceItem](
      s"select Id, ChargeAmount, TaxAmount, ServiceStartDate, ServiceEndDate, ChargeNumber, ProductName, SubscriptionId " +
        s"from invoiceitem where SubscriptionNumber = '${subscriptionNumber.getNumber}'",
    ).map(invoiceItems => PaymentSummary(latestInvoiceItems(invoiceItems), accountCurrency))

  def getPaymentMethod(id: String)(implicit logPrefix: LogPrefix): Future[SoapQueries.PaymentMethod] =
    getObject[SoapQueries.PaymentMethod](s"object/payment-method/$id")

}
