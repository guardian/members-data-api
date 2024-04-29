package com.gu.zuora.api

import com.gu.i18n.Currency
import com.gu.memsub.{Subscription => S}
import com.gu.stripe.Stripe
import com.gu.zuora.soap.models.Queries.PreviewInvoiceItem
import com.gu.zuora.soap.models.Results.UpdateResult
import com.gu.zuora.soap.models.{PaymentSummary, Queries => SoapQueries}

import scala.concurrent.Future

trait ZuoraService {

  def getAccount(accountId: S.AccountId): Future[SoapQueries.Account]

  def getSubscription(id: S.Id): Future[SoapQueries.Subscription]

  def previewInvoices(subscriptionId: S.Id, number: Int = 2): Future[Seq[PreviewInvoiceItem]]

  def createCreditCardPaymentMethod(
      accountId: S.AccountId,
      stripeCustomer: Stripe.Customer,
      paymentGateway: PaymentGateway,
      invoiceTemplateOverride: Option[InvoiceTemplate],
  ): Future[UpdateResult]

  def getPaymentSummary(subscriptionNumber: S.Name, accountCurrency: Currency): Future[PaymentSummary]

  def getPaymentMethod(id: String): Future[SoapQueries.PaymentMethod]

}
