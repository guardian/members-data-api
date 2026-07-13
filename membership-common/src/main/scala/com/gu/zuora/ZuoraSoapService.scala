package com.gu.zuora

import com.gu.i18n.{CountryGroup, Currency}
import com.gu.memsub.Subscription._
import com.gu.memsub.{Subscription => S}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.gu.salesforce.ContactId
import com.gu.stripe.Stripe
import com.gu.zuora.api.{PaymentGateway}
import com.gu.zuora.soap._
import com.gu.zuora.soap.models.Commands.CreatePaymentMethod
import com.gu.zuora.soap.models.Results.UpdateResult
import com.gu.zuora.soap.models.errors._
import com.gu.zuora.soap.models.{PaymentSummary, Queries => SoapQueries}
import com.gu.zuora.rest.ZuoraQueryReads._
import com.gu.zuora.rest.ZuoraPaymentWrites._
import com.gu.zuora.rest.{ZuoraResponse, zuoraResponseReads}
import play.api.libs.json.{JsObject, Reads}

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

class ZuoraSoapService(restClient: rest.SimpleClient[Future])(implicit ec: ExecutionContext) extends SoapClient[Future] with SafeLogging {

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
    query[AccountId](s"select Id from account where crmId = '${contactId.salesforceAccountId}'")

  def getAccount(accountId: AccountId)(implicit logPrefix: LogPrefix): Future[SoapQueries.Account] =
    getObject[SoapQueries.Account](s"object/account/${accountId.get}")

  def getContact(contactId: String)(implicit logPrefix: LogPrefix): Future[SoapQueries.Contact] =
    getObject[SoapQueries.Contact](s"object/contact/$contactId")

  /* These payment-method writes used to go through the SOAP create/update API; they now hit the equivalent Zuora REST endpoints: the account payment
     fields via PUT accounts/{id}, and the payment method itself via POST object/payment-method (which takes the same zObject fields the SOAP create
     used). A REST error or an unsuccessful response fails the Future, matching the old behaviour where a non-success SOAP response raised. */
  private def updateAccountPayment(
      accountId: AccountId,
      defaultPaymentMethodId: Option[String],
      paymentGateway: PaymentGateway,
      autoPay: Boolean,
  )(implicit logPrefix: LogPrefix): Future[UpdateResult] =
    restClient
      .put[AccountPaymentUpdate, ZuoraResponse](
        s"accounts/${accountId.get}",
        AccountPaymentUpdate(defaultPaymentMethodId, paymentGateway.gatewayName, autoPay),
      )
      .map(_.valueOr(error => throw QueryError(s"Zuora REST account update for ${accountId.get} failed: $error")))
      .map { response =>
        if (response.success) UpdateResult(accountId.get)
        else throw QueryError(s"Zuora account update for ${accountId.get} was unsuccessful: ${response.error.getOrElse("")}")
      }

  private def createObjectPaymentMethod(zObject: JsObject)(implicit logPrefix: LogPrefix): Future[String] =
    restClient
      .post[JsObject, ObjectCreateResponse]("object/payment-method", zObject)
      .map(_.valueOr(error => throw QueryError(s"Zuora REST create payment method failed: $error")))
      .map(response => if (response.success) response.id else throw QueryError("Zuora create payment method was unsuccessful"))

  private def setDefaultPaymentMethod(accountId: AccountId, paymentMethodId: String, paymentGateway: PaymentGateway)(implicit
      logPrefix: LogPrefix,
  ): Future[UpdateResult] =
    updateAccountPayment(accountId, defaultPaymentMethodId = Some(paymentMethodId), paymentGateway, autoPay = true)

  // Clear the default payment method before switching the gateway: Zuora requires the account gateway to match its default method's gateway.
  private def setGatewayAndClearDefaultMethod(accountId: AccountId, paymentGateway: PaymentGateway)(implicit
      logPrefix: LogPrefix,
  ): Future[UpdateResult] =
    updateAccountPayment(accountId, defaultPaymentMethodId = None, paymentGateway, autoPay = false)

  /* Creates a payment method and sets it as default. Still three steps because Zuora validates that the account's gateway matches its default method's,
     so we cannot swap gateway and method atomically: clear the old default onto the new gateway, create the method, then set it default. */
  def createPaymentMethod(command: CreatePaymentMethod)(implicit logPrefix: LogPrefix): Future[UpdateResult] = for {
    _ <- setGatewayAndClearDefaultMethod(command.accountId, command.paymentGateway)
    paymentMethodId <- createObjectPaymentMethod(paymentMethod(command.accountId.get, command.paymentMethod))
    result <- setDefaultPaymentMethod(command.accountId, paymentMethodId, command.paymentGateway)
  } yield result

  def createCreditCardPaymentMethod(
      accountId: AccountId,
      stripeCustomer: Stripe.Customer,
      paymentGateway: PaymentGateway,
  )(implicit logPrefix: LogPrefix): Future[UpdateResult] = {
    val card = stripeCustomer.card
    for {
      _ <- setGatewayAndClearDefaultMethod(accountId, paymentGateway)
      paymentMethodId <- createObjectPaymentMethod(
        creditCardReference(
          accountId = accountId.get,
          cardId = card.id,
          customerId = stripeCustomer.id,
          last4 = card.last4,
          cardCountryAlpha2 = CountryGroup.countryByCode(card.country).map(_.alpha2),
          expirationMonth = card.exp_month,
          expirationYear = card.exp_year,
          cardType = card.`type`,
        ),
      )
      result <- setDefaultPaymentMethod(accountId, paymentMethodId, paymentGateway)
    } yield result
  }

  def getPaymentSummary(subscriptionNumber: S.SubscriptionNumber, accountCurrency: Currency)(implicit
      logPrefix: LogPrefix,
  ): Future[PaymentSummary] = {
    val zoql = List(
      "select Id, ChargeAmount, TaxAmount, ServiceStartDate, ServiceEndDate, ChargeNumber, ProductName, SubscriptionId",
      "from invoiceitem",
      s"where SubscriptionNumber = '${subscriptionNumber.getNumber}'",
    ).mkString(" ")
    for {
      invoiceItems <- query[SoapQueries.InvoiceItem](zoql)
    } yield {
      val filteredInvoices = latestInvoiceItems(invoiceItems)
      PaymentSummary(filteredInvoices, accountCurrency)
    }
  }

  def getPaymentMethod(id: String)(implicit logPrefix: LogPrefix): Future[SoapQueries.PaymentMethod] =
    getObject[SoapQueries.PaymentMethod](s"object/payment-method/$id")

}
