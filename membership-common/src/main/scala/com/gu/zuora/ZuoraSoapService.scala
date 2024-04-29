package com.gu.zuora

import com.gu.i18n.{CountryGroup, Currency}
import com.gu.memsub.Subscription._
import com.gu.memsub.{Subscription => S}
import com.gu.monitoring.SafeLogging
import com.gu.stripe.Stripe
import com.gu.zuora.api.{InvoiceTemplate, PaymentGateway}
import com.gu.zuora.soap.Readers._
import com.gu.zuora.soap._
import com.gu.zuora.soap.actions.Action
import com.gu.zuora.soap.actions.Actions._
import com.gu.zuora.soap.models.Queries.PreviewInvoiceItem
import com.gu.zuora.soap.models.Results.{AmendResult, UpdateResult}
import com.gu.zuora.soap.models.errors._
import com.gu.zuora.soap.models.{PaymentSummary, Queries => SoapQueries}
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

class ZuoraSoapService(soapClient: soap.ClientWithFeatureSupplier)(implicit ec: ExecutionContext) extends api.ZuoraService with SafeLogging {

  import ZuoraSoapService._

  override def getAccount(accountId: AccountId): Future[SoapQueries.Account] =
    soapClient.queryOne[SoapQueries.Account](SimpleFilter("id", accountId.get))

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
