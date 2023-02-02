package services.zuora.soap

import com.gu.i18n.{CountryGroup, Currency}
import monitoring.SafeLogger
import monitoring.SafeLogger._
import services.salesforce.model.ContactId
import com.gu.stripe.Stripe
import com.gu.zuora.api.{InvoiceTemplate, PayPal, PaymentGateway}
import services.zuora.soap.actions.{Action, XmlWriterAction}
import services.zuora.soap.models.Queries.{PreviewInvoiceItem, Subscription => QuerySubscription}
import services.zuora.soap.models.Results.{AmendResult, CreateResult, SubscribeResult, UpdateResult}
import services.zuora.soap.models.{PaymentSummary, Queries => SoapQueries}
import org.joda.time.{DateTime, LocalDate, ReadableDuration}
import _root_.models.subscription.Subscription.AccountId
import _root_.models.subscription.Subscription
import _root_.models.subscription.Subscription.RatePlanId
import _root_.models.subscription.Subscription.ProductRatePlanId
import _root_.models.subscription.promo.PromoCode
import services.zuora.soap.actions.Actions.{
  CancelPlan,
  Clear,
  CreateCreditCardReferencePaymentMethod,
  CreateFreeEventUsage,
  CreatePayPalReferencePaymentMethod,
  DowngradePlan,
  PreviewInvoicesTillEndOfTermViaAmend,
  PreviewInvoicesViaAmend,
  SetTo,
  Update,
  UpdateAccountPayment,
}
import services.zuora.soap.models.Commands.{Amend, Contribute, CreatePaymentMethod, Renew, Subscribe, UpdatePromoCode}
import services.zuora.soap.writers.Command

import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds
import scala.util.{Failure, Success}

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

class SimpleZuoraSoapService(soapClient: ClientWithFeatureSupplier)(implicit ec: ExecutionContext) extends ZuoraSoapService {

  import Readers._
  import SimpleZuoraSoapService._
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

  override def getSubscription(id: Subscription.Id): Future[SoapQueries.Subscription] =
    soapClient.queryOne[SoapQueries.Subscription](SimpleFilter("id", id.get))

  private def previewInvoices(subscriptionId: String, paymentDate: LocalDate, action: (String, LocalDate) => Action[AmendResult]) = {
    val invoices = soapClient.authenticatedRequest(action(subscriptionId, paymentDate)).map(_.invoiceItems)
    invoices recover {
      case e: Error => Nil
      case e: Throwable => throw e
    }
  }

  override def previewInvoicesTillEndOfTerm(subscriptionId: Subscription.Id): Future[Seq[PreviewInvoiceItem]] = {
    for {
      sub <- getSubscription(subscriptionId)
      previewInvoiceItems <- previewInvoices(sub.id, sub.contractAcceptanceDate, PreviewInvoicesTillEndOfTermViaAmend)
    } yield previewInvoiceItems
  }

  override def previewInvoices(subscriptionId: Subscription.Id, number: Int = 2): Future[Seq[PreviewInvoiceItem]] = {
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
    createMethodResult <- soapClient.extendedAuthenticatedRequest[CreateResult](new XmlWriterAction(command)(Command.createPaymentMethodWrites))
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
    val amendResult = soapClient.authenticatedRequest[AmendResult](new XmlWriterAction(renew)(Command.renewWrites))

    val promoCode = Some(renew).flatMap(_.promoCode)

    promoCode.foreach { code =>
      amendResult.map { _ => // wait for the amend to complete
        updatePromoCode(code, renew.subscriptionId, amendResult).onComplete {
          case Success(_) => SafeLogger.info("updated promo code")
          case Failure(e) => SafeLogger.error(scrub"ZU002: failed to update promo code", e)
        }
      }
    }

    amendResult
  }

  override def upgradeSubscription(amend: Amend): Future[AmendResult] = {
    val amendResult = soapClient.authenticatedRequest[AmendResult](new XmlWriterAction(amend)(Command.amendWrites))

    val promoCode = Some(amend).filterNot(_.previewMode).flatMap(_.promoCode)

    promoCode.foreach { code =>
      amendResult.map { _ => // wait for the amend to complete
        updatePromoCode(code, amend.subscriptionId, amendResult).onComplete {
          case Success(_) => SafeLogger.info("updated promo code")
          case Failure(e) => SafeLogger.error(scrub"ZU001: failed to update promo code", e)
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
      subscription <- soapClient.queryOne[QuerySubscription](SimpleFilter("id", subscriptionId))
      allSubVersions <- soapClient.query[QuerySubscription](SimpleFilter("Name", subscription.name))
      latestSubscriptionId = allSubVersions.sortBy(_.version).map(_.id).last
      action = UpdatePromoCode(latestSubscriptionId, code.get)
      _ <- soapClient.authenticatedRequest[UpdateResult](new XmlWriterAction(action)(Command.updatePromoCodeWrites))
    } yield ()

  }

  override def downgradePlan(
      subscriptionId: Subscription.Id,
      currentRatePlan: RatePlanId,
      futureRatePlanId: ProductRatePlanId,
      effectiveFrom: LocalDate,
  ): Future[AmendResult] =
    soapClient.authenticatedRequest(
      DowngradePlan(subscriptionId.get, currentRatePlan.get, futureRatePlanId.get, effectiveFrom),
    )

  override def cancelPlan(subscription: Subscription.Id, rp: RatePlanId, cancelDate: LocalDate) =
    soapClient.authenticatedRequest(CancelPlan(subscription.get, rp.get, cancelDate))

  implicit private def features: Future[Seq[SoapQueries.Feature]] = soapClient.featuresSupplier.get()

  override def getPaymentSummary(subscriptionNumber: Subscription.Name, accountCurrency: Currency): Future[PaymentSummary] =
    for {
      invoiceItems <- soapClient.query[SoapQueries.InvoiceItem](SimpleFilter("SubscriptionNumber", subscriptionNumber.get))
    } yield {
      val filteredInvoices = latestInvoiceItems(invoiceItems)
      PaymentSummary(filteredInvoices, accountCurrency)
    }

  override def getUsages(subscriptionNumber: Subscription.Name, unitOfMeasure: String, startDate: DateTime): Future[Seq[SoapQueries.Usage]] =
    soapClient.query[SoapQueries.Usage](
      AndFilter(
        SimpleFilter("StartDateTime", DateTimeHelpers.formatDateTime(startDate), ">="),
        ("SubscriptionNumber", subscriptionNumber.get),
        ("UOM", unitOfMeasure),
      ),
    )

  override def createFreeEventUsage(
      accountId: AccountId,
      subscriptionNumber: Subscription.Name,
      description: String,
      quantity: Int,
  ): Future[CreateResult] =
    soapClient.authenticatedRequest(CreateFreeEventUsage(accountId.get, description, quantity, subscriptionNumber.get))

  override def getFeatures: Future[Seq[SoapQueries.Feature]] = soapClient.featuresSupplier.get()

  override def createSubscription(subscribe: Subscribe): Future[SubscribeResult] =
    soapClient.extendedAuthenticatedRequest[SubscribeResult](new XmlWriterAction(subscribe)(Command.subscribeWrites))

  override def lastPingTimeWithin(duration: ReadableDuration) = soapClient.lastPingTimeWithin(duration)

  override def getPaymentMethod(id: String): Future[SoapQueries.PaymentMethod] =
    soapClient.queryOne[SoapQueries.PaymentMethod](SimpleFilter("Id", id))

  override def updateActivationDate(subscriptionId: Subscription.Id): Future[Unit] =
    soapClient.authenticatedRequest[UpdateResult](
      Update(subscriptionId.get, "Subscription", Seq("ActivationDate__c" -> DateTime.now().toString)),
    ) map (_ => ()) andThen {
      case Success(_) => SafeLogger.debug(s"Updated activation date for subscription ${subscriptionId.get}")
      case Failure(e) => SafeLogger.error(scrub"Error while trying to update activation date for subscription: ${subscriptionId.get}", e)
    }

  override def createContribution(contribute: Contribute): Future[SubscribeResult] =
    soapClient.extendedAuthenticatedRequest[SubscribeResult](new XmlWriterAction(contribute)(Command.contributeWrites))

}
