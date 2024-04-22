package com.gu.zuora

import com.gu.i18n.{CountryGroup, Currency}
import com.gu.memsub.Subscription._
import com.gu.memsub.promo.PromoCode
import com.gu.memsub.{Subscription => S}
import com.gu.monitoring.SafeLogging
import com.gu.salesforce.ContactId
import com.gu.stripe.Stripe
import com.gu.zuora.api.{InvoiceTemplate, PayPal, PaymentGateway}
import com.gu.zuora.soap.Readers._
import com.gu.zuora.soap.ZuoraFilter._
import com.gu.zuora.soap._
import com.gu.zuora.soap.actions.Actions._
import com.gu.zuora.soap.actions.{Action, XmlWriterAction}
import com.gu.zuora.soap.models.Commands._
import com.gu.zuora.soap.models.Queries.{PreviewInvoiceItem, Subscription}
import com.gu.zuora.soap.models.Results.{AmendResult, CreateResult, SubscribeResult, UpdateResult}
import com.gu.zuora.soap.models.errors._
import com.gu.zuora.soap.models.{PaymentSummary, Queries => SoapQueries}
import com.gu.zuora.soap.writers.Command._
import org.joda.time.{DateTime, LocalDate, ReadableDuration}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds
import scala.util.{Failure, Success}

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
  implicit private val sc = soapClient

  def getAccounts(contactId: ContactId): Future[Seq[SoapQueries.Account]] =
    soapClient.query[SoapQueries.Account](SimpleFilter("crmId", contactId.salesforceAccountId))

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

  override def previewInvoicesTillEndOfTerm(subscriptionId: S.Id): Future[Seq[PreviewInvoiceItem]] = {
    for {
      sub <- getSubscription(subscriptionId)
      previewInvoiceItems <- previewInvoices(sub.id, sub.contractAcceptanceDate, PreviewInvoicesTillEndOfTermViaAmend)
    } yield previewInvoiceItems
  }

  override def previewInvoices(subscriptionId: S.Id, number: Int = 2): Future[Seq[PreviewInvoiceItem]] = {
    for {
      sub <- getSubscription(subscriptionId)
      previewInvoiceItems <- previewInvoices(sub.id, sub.contractAcceptanceDate, PreviewInvoicesViaAmend(number) _)
    } yield previewInvoiceItems
  }

  override def previewInvoices(subscriptionId: String, contractAcceptanceDate: LocalDate, number: Int): Future[Seq[PreviewInvoiceItem]] = {
    previewInvoices(subscriptionId, contractAcceptanceDate, PreviewInvoicesViaAmend(number) _)
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

  override def createPayPalPaymentMethod(accountId: AccountId, payPalBaid: String, email: String): Future[UpdateResult] =
    for {
      r <- setGatewayAndClearDefaultMethod(accountId, PayPal) // We need to set gateway correctly because it must match with the payment method
      paymentMethod <- soapClient.authenticatedRequest(CreatePayPalReferencePaymentMethod(accountId.get, payPalBaid, email))
      result <- setDefaultPaymentMethod(accountId, paymentMethod.id, PayPal, None)
    } yield result

  override def renewSubscription(renew: Renew): Future[AmendResult] = {
    val amendResult = soapClient.authenticatedRequest[AmendResult](new XmlWriterAction(renew)(renewWrites))

    val promoCode = Some(renew).flatMap(_.promoCode)

    promoCode.foreach { code =>
      amendResult.map { _ => // wait for the amend to complete
        updatePromoCode(code, renew.subscriptionId, amendResult).onComplete {
          case Success(_) => logger.info("updated promo code")
          case Failure(e) => logger.error(scrub"ZU002: failed to update promo code", e)
        }
      }
    }

    amendResult
  }

  override def upgradeSubscription(upgrade: Amend): Future[AmendResult] = {
    val amendResult = soapClient.authenticatedRequest[AmendResult](new XmlWriterAction(upgrade)(amendWrites))

    val promoCode = Some(upgrade).filterNot(_.previewMode).flatMap(_.promoCode)

    promoCode.foreach { code =>
      amendResult.map { _ => // wait for the amend to complete
        updatePromoCode(code, upgrade.subscriptionId, amendResult).onComplete {
          case Success(_) => logger.info("updated promo code")
          case Failure(e) => logger.error(scrub"ZU001: failed to update promo code", e)
        }
      }
    }

    amendResult
  }

  private def updatePromoCode(code: PromoCode, subscriptionId: String, amendResult: Future[AmendResult]) = {
    /*
     * You can't amend promo codes so we need to do an actual update too to change the promo code
     * on the latest version of the subscription. This takes a lot of effort but happens in the background
     *
     * We have to find the name of the subscription we amended, then get the latest version of that.
     * We can't go from the last amendment to the last subscription ID as the sub ID points to the subscription
     * it amended, not the new version post amendment.
     */

    for {
      subscription <- soapClient.queryOne[Subscription](SimpleFilter("id", subscriptionId))
      allSubVersions <- soapClient.query[Subscription](SimpleFilter("Name", subscription.name))
      latestSubscriptionId = allSubVersions.sortBy(_.version).map(_.id).last
      action = UpdatePromoCode(latestSubscriptionId, code.get)
      _ <- soapClient.authenticatedRequest[UpdateResult](new XmlWriterAction(action)(updatePromoCodeWrites))
    } yield ()

  }

  override def downgradePlan(
      subscriptionId: S.Id,
      currentRatePlan: RatePlanId,
      futureRatePlanId: ProductRatePlanId,
      effectiveFrom: LocalDate,
  ): Future[AmendResult] =
    soapClient.authenticatedRequest(
      DowngradePlan(subscriptionId.get, currentRatePlan.get, futureRatePlanId.get, effectiveFrom),
    )

  override def cancelPlan(subscription: S.Id, rp: RatePlanId, cancelDate: LocalDate) =
    soapClient.authenticatedRequest(CancelPlan(subscription.get, rp.get, cancelDate))

  implicit private def features: Future[Seq[SoapQueries.Feature]] = soapClient.featuresSupplier.get()

  override def getPaymentSummary(subscriptionNumber: S.Name, accountCurrency: Currency): Future[PaymentSummary] =
    for {
      invoiceItems <- soapClient.query[SoapQueries.InvoiceItem](SimpleFilter("SubscriptionNumber", subscriptionNumber.get))
    } yield {
      val filteredInvoices = latestInvoiceItems(invoiceItems)
      PaymentSummary(filteredInvoices, accountCurrency)
    }

  override def getUsages(subscriptionNumber: S.Name, unitOfMeasure: String, startDate: DateTime): Future[Seq[SoapQueries.Usage]] =
    soapClient.query[SoapQueries.Usage](
      AndFilter(
        SimpleFilter("StartDateTime", DateTimeHelpers.formatDateTime(startDate), ">="),
        ("SubscriptionNumber", subscriptionNumber.get),
        ("UOM", unitOfMeasure),
      ),
    )

  override def createFreeEventUsage(accountId: AccountId, subscriptionNumber: S.Name, description: String, quantity: Int): Future[CreateResult] =
    soapClient.authenticatedRequest(CreateFreeEventUsage(accountId.get, description, quantity, subscriptionNumber.get))

  override def getFeatures: Future[Seq[SoapQueries.Feature]] = soapClient.featuresSupplier.get()

  override def createSubscription(sub: Subscribe): Future[SubscribeResult] =
    soapClient.extendedAuthenticatedRequest[SubscribeResult](new XmlWriterAction(sub)(subscribeWrites))

  override def lastPingTimeWithin(d: ReadableDuration) = soapClient.lastPingTimeWithin(d)

  override def getPaymentMethod(id: String): Future[SoapQueries.PaymentMethod] =
    soapClient.queryOne[SoapQueries.PaymentMethod](SimpleFilter("Id", id))

  override def updateActivationDate(subscriptionId: Id): Future[Unit] =
    soapClient.authenticatedRequest[UpdateResult](
      Update(subscriptionId.get, "Subscription", Seq("ActivationDate__c" -> DateTime.now().toString)),
    ) map (_ => ()) andThen {
      case Success(_) => logger.debug(s"Updated activation date for subscription ${subscriptionId.get}")
      case Failure(e) => logger.error(scrub"Error while trying to update activation date for subscription: ${subscriptionId.get}", e)
    }

  override def createContribution(con: Contribute): Future[SubscribeResult] =
    soapClient.extendedAuthenticatedRequest[SubscribeResult](new XmlWriterAction(con)(contributeWrites))

}
