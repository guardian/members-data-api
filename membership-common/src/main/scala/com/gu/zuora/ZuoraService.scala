package com.gu.zuora

import com.gu.i18n.{CountryGroup, Currency}
import com.gu.memsub.Subscription._
import com.gu.memsub.{Subscription => S}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.gu.salesforce.ContactId
import com.gu.stripe.Stripe
import com.gu.zuora.api.{PaymentGateway}
import com.gu.zuora.models.Commands.{CreatePaymentMethod, CreditCardReferenceTransaction}
import com.gu.zuora.models.errors._
import com.gu.zuora.models.{PaymentSummary, Queries}
import com.gu.zuora.rest.ZuoraQueryReads._
import com.gu.zuora.rest.ZuoraPaymentWrites._
import com.gu.zuora.rest.{ZuoraResponse, zuoraResponseReads}
import play.api.libs.json.Reads

import scala.concurrent.{ExecutionContext, Future}

object ZuoraService {

  def latestInvoiceItems(items: Seq[Queries.InvoiceItem]): Seq[Queries.InvoiceItem] = {
    if (items.isEmpty)
      items
    else {
      val sortedItems = items.sortBy(_.chargeNumber)
      sortedItems.filter(_.subscriptionId == sortedItems.last.subscriptionId)
    }
  }
}

trait ZuoraClient[M[_]] {

  def getAccountIds(contactId: ContactId)(implicit logPrefix: LogPrefix): M[List[AccountId]]
}

class ZuoraService(restClient: rest.SimpleClient[Future])(implicit ec: ExecutionContext) extends ZuoraClient[Future] with SafeLogging {

  import ZuoraService._

  /* getObject fetches a single object via object/{type}/{id} and fails the Future if it is missing or the call errors; query runs a ZOQL query via
     action/query and returns the records (empty if none), failing only on a REST error. */
  private def getObject[A: Reads](url: String)(implicit logPrefix: LogPrefix): Future[A] =
    restClient.get[A](url).map(_.valueOr(error => throw QueryError(s"Zuora REST get '$url' failed: $error")))

  private def query[A: Reads](zoql: String)(implicit logPrefix: LogPrefix): Future[List[A]] =
    restClient
      .post[RestQuery, QueryResponse[A]]("action/query", RestQuery(zoql))
      .map(_.valueOr(error => throw QueryError(s"Zuora REST query '$zoql' failed: $error")).records)

  def getAccountIds(contactId: ContactId)(implicit logPrefix: LogPrefix): Future[List[AccountId]] =
    query[AccountId](s"select Id from account where crmId = '${contactId.salesforceAccountId}'")

  def getAccount(accountId: AccountId)(implicit logPrefix: LogPrefix): Future[Queries.Account] =
    getObject[Queries.Account](s"object/account/${accountId.get}")

  def getContact(contactId: String)(implicit logPrefix: LogPrefix): Future[Queries.Contact] =
    getObject[Queries.Contact](s"object/contact/$contactId")

  /* The account payment fields are updated via PUT accounts/{id}, and the payment method is created via POST object/payment-method. A REST error or an
     unsuccessful response fails the Future. */
  private def updateAccountPayment(
      accountId: AccountId,
      defaultPaymentMethodId: Option[String],
      paymentGateway: PaymentGateway,
      autoPay: Boolean,
  )(implicit logPrefix: LogPrefix): Future[Unit] =
    restClient
      .put[AccountPaymentUpdate, ZuoraResponse](
        s"accounts/${accountId.get}",
        AccountPaymentUpdate(defaultPaymentMethodId, paymentGateway.gatewayName, autoPay),
      )
      .map(_.valueOr(error => throw QueryError(s"Zuora REST account update for ${accountId.get} failed: $error")))
      .map { response =>
        if (response.success) ()
        else throw QueryError(s"Zuora account update for ${accountId.get} was unsuccessful: ${response.error.getOrElse("")}")
      }

  private def createObjectPaymentMethod(request: CreatePaymentMethodObject)(implicit logPrefix: LogPrefix): Future[String] =
    restClient
      .post[CreatePaymentMethodObject, ObjectCreateResult]("object/payment-method", request)
      .map(_.valueOr(error => throw QueryError(s"Zuora REST create payment method failed: $error")))
      .map {
        case ObjectCreateResult.Created(id) => id
        case ObjectCreateResult.Failed(reason) => throw QueryError(s"Zuora create payment method was unsuccessful: $reason")
      }

  private def setDefaultPaymentMethod(accountId: AccountId, paymentMethodId: String, paymentGateway: PaymentGateway)(implicit
      logPrefix: LogPrefix,
  ): Future[Unit] =
    updateAccountPayment(accountId, defaultPaymentMethodId = Some(paymentMethodId), paymentGateway, autoPay = true)

  // Clear the default payment method before switching the gateway: Zuora requires the account gateway to match its default method's gateway.
  private def setGatewayAndClearDefaultMethod(accountId: AccountId, paymentGateway: PaymentGateway)(implicit
      logPrefix: LogPrefix,
  ): Future[Unit] =
    updateAccountPayment(accountId, defaultPaymentMethodId = None, paymentGateway, autoPay = false)

  /* Creates a payment method and sets it as default. Still three steps because Zuora validates that the account's gateway matches its default method's,
     so we cannot swap gateway and method atomically: clear the old default onto the new gateway, create the method, then set it default. */
  def createPaymentMethod(command: CreatePaymentMethod)(implicit logPrefix: LogPrefix): Future[Unit] = for {
    _ <- setGatewayAndClearDefaultMethod(command.accountId, command.paymentGateway)
    paymentMethodId <- createObjectPaymentMethod(CreatePaymentMethodObject(command.accountId, command.paymentMethod))
    _ <- setDefaultPaymentMethod(command.accountId, paymentMethodId, command.paymentGateway)
  } yield ()

  def createCreditCardPaymentMethod(
      accountId: AccountId,
      stripeCustomer: Stripe.Customer,
      paymentGateway: PaymentGateway,
  )(implicit logPrefix: LogPrefix): Future[Unit] = {
    val card = stripeCustomer.card
    val paymentMethod = CreditCardReferenceTransaction(
      cardId = card.id,
      customerId = stripeCustomer.id,
      last4 = card.last4,
      cardCountry = CountryGroup.countryByCode(card.country),
      expirationMonth = card.exp_month,
      expirationYear = card.exp_year,
      cardType = card.`type`,
    )
    for {
      _ <- setGatewayAndClearDefaultMethod(accountId, paymentGateway)
      paymentMethodId <- createObjectPaymentMethod(CreatePaymentMethodObject(accountId, paymentMethod))
      _ <- setDefaultPaymentMethod(accountId, paymentMethodId, paymentGateway)
    } yield ()
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
      invoiceItems <- query[Queries.InvoiceItem](zoql)
    } yield {
      val filteredInvoices = latestInvoiceItems(invoiceItems)
      PaymentSummary(filteredInvoices, accountCurrency)
    }
  }

  def getPaymentMethod(id: String)(implicit logPrefix: LogPrefix): Future[Queries.PaymentMethod] =
    getObject[Queries.PaymentMethod](s"object/payment-method/$id")

}
