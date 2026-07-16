package services.zuora.payment

import com.gu.memsub.BillingSchedule.Bill
import com.gu.memsub.Subscription._
import com.gu.memsub.promo.LogImplicit._
import com.gu.memsub.subsv2.{Catalog, Subscription}
import com.gu.memsub.{Subscription => _, _}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.Payment
import com.gu.zuora.ZuoraService
import com.gu.zuora.models.Queries
import com.gu.zuora.models.Queries.Account
import org.joda.time.LocalDate
import services.zuora.rest.ZuoraRestService
import scalaz.{-\/, \/-}
import scalaz.std.option._
import scalaz.syntax.monad._
import scalaz.syntax.std.option._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class PaymentService(zuoraService: ZuoraService, restService: ZuoraRestService)(implicit ec: ExecutionContext) extends SafeLogging {

  def paymentDetails(
      sub: Subscription,
      defaultMandateIdIfApplicable: Option[String] = None,
      catalog: Catalog,
  )(implicit logPrefix: LogPrefix): Future[PaymentDetails] = {
    val currency = sub.plan(catalog).chargesPrice.currencies.head
    // I am not convinced this function is very safe, hence the option
    val eventualMaybeLastPaymentDate = zuoraService
      .getPaymentSummary(sub.subscriptionNumber, currency)
      .map(_.current.serviceStartDate.some)
      .recover { case _ => None }
    eventualMaybeLastPaymentDate.withLogging(s"lastPaymentDate for $sub")

    for {
      account <- zuoraService.getAccount(sub.accountId)
      // Preview ~30 months ahead to handle long promotional periods (e.g. Australian student offer: 24 months free),
      // so we still find the first paid bill for those. Like-for-like with the previous SOAP 30-billing-periods request.
      eventualBills = getNextBill(sub.subscriptionNumber, account, LocalDate.now.plusMonths(30)).withLogging(s"next bill for $sub")
      eventualMaybePaymentMethod = getPaymentMethod(account.defaultPaymentMethodId, defaultMandateIdIfApplicable) // kick off async
      bills <- eventualBills
      maybePaymentMethod <- eventualMaybePaymentMethod
      lpd <- eventualMaybeLastPaymentDate
    } yield {
      val maybePayment = bills.find(_.amount > 0).map(bill => Payment(Price(bill.amount, currency), bill.date))
      val maybeFirstInvoiceDate = bills.headOption.map(_.date)
      PaymentDetails.fromSubAndPaymentData(sub, maybePaymentMethod, maybePayment, maybeFirstInvoiceDate, lpd, catalog)
    }
  }

  private def buildBankTransferPaymentMethod(
      defaultMandateIdIfApplicable: Option[String],
      m: ZuoraRestService.PaymentMethodResponse,
  ): Option[PaymentMethod] = {
    for {
      mandateId <- m.mandateId.orElse(defaultMandateIdIfApplicable)
      accountName <- m.bankTransferAccountName
      accountNumber <- m.bankTransferAccountNumberMask
      paymentMethod <-
        (m.bankTransferType, m.bankCode) match {
          case (Some("SEPA"), _) =>
            Some(Sepa(mandateId, accountName, accountNumber, m.numConsecutiveFailures, m.paymentMethodStatus))
          case (_, Some(sortCode)) =>
            Some(GoCardless(mandateId, accountName, accountNumber, sortCode, m.numConsecutiveFailures, m.paymentMethodStatus))
          case _ => None
        }
    } yield paymentMethod
  }

  private def buildPaymentMethod(
      defaultMandateIdIfApplicable: Option[String] = None,
      m: ZuoraRestService.PaymentMethodResponse,
  ): Option[PaymentMethod] =
    m.paymentMethodType match {
      case "CreditCard" | "CreditCardReferenceTransaction" =>
        val isReferenceTransaction = m.paymentMethodType == "CreditCardReferenceTransaction"
        val details =
          (m.creditCardNumber |@| m.creditCardExpirationMonth |@| m.creditCardExpirationYear)(PaymentCardDetails)
        Some(PaymentCard(isReferenceTransaction, m.creditCardType, details, m.numConsecutiveFailures, m.paymentMethodStatus))
      case "BankTransfer" =>
        buildBankTransferPaymentMethod(defaultMandateIdIfApplicable, m)
      case "PayPal" =>
        Some(PayPalMethod(m.payPalEmail.get, m.numConsecutiveFailures, m.paymentMethodStatus))
      case _ => None
    }

  private def getNextBill(subscriptionNumber: SubscriptionNumber, account: Account, targetDate: LocalDate)(implicit
      logPrefix: LogPrefix,
  ): Future[List[Bill]] =
    for {
      previewInvoiceItems <- getPreviewInvoiceItems(subscriptionNumber, AccountId(account.id), targetDate)
    } yield for {
      billingSched <- BillingSchedule.fromPreviewInvoiceItems(previewInvoiceItems).toList
      bill <- billingSched
        .withCreditBalanceApplied(account.creditBalance)
        .invoices
        .list
        .toList
    } yield bill

  /** billing-preview is account-scoped, so we request the whole account and keep only the target subscription's items. We match on the subscription
    * number, not the Zuora id: with assumeRenewal the projected items carry a simulated renewal subscription id, so filtering by id would drop them
    * all. A preview failure only means we show no next payment; it must never fail the whole /mma call, so we log it and carry on.
    */
  private def getPreviewInvoiceItems(subscriptionNumber: SubscriptionNumber, accountId: AccountId, targetDate: LocalDate)(implicit
      logPrefix: LogPrefix,
  ): Future[Seq[Queries.PreviewInvoiceItem]] =
    restService
      .getBillingPreview(accountId, targetDate)
      .map {
        case \/-(items) =>
          items.filter(_.subscriptionNumber == subscriptionNumber).map(PaymentService.toPreviewInvoiceItem)
        case -\/(error) =>
          logger.warn(s"could not get billing preview for account ${accountId.get}, showing no next payment: $error")
          Nil
      }
      .recover { case error =>
        logger.warn(s"could not get billing preview for account ${accountId.get}, showing no next payment", error)
        Nil
      }

  def getPaymentMethod(maybePaymentMethodId: Option[String], defaultMandateIdIfApplicable: Option[String] = None)(implicit
      logPrefix: LogPrefix,
  ): Future[Option[PaymentMethod]] =
    (for {
      paymentMethodId <- maybePaymentMethodId
    } yield restService
      .getPaymentMethod(paymentMethodId)
      .withLogging(s"get payment method for $maybePaymentMethodId")
      .map {
        case \/-(paymentMethod) => buildPaymentMethod(defaultMandateIdIfApplicable, paymentMethod)
        case -\/(error) => throw new RuntimeException(s"Failed to get payment method $paymentMethodId: $error")
      })
      .getOrElse(Future.successful(None))

}

object PaymentService {

  /** Map a Zuora billing-preview invoice item to the internal preview shape BillingSchedule consumes. price includes tax to stay like-for-like with
    * the old SOAP amend-with-preview. productId and productRatePlanChargeId are not returned by billing-preview and are unused by BillingSchedule.
    */
  def toPreviewInvoiceItem(item: ZuoraRestService.BillingPreviewInvoiceItem): Queries.PreviewInvoiceItem = {
    val grossPrice = (item.chargeAmount + item.taxAmount).toFloat
    Queries.PreviewInvoiceItem(
      price = grossPrice,
      serviceStartDate = new LocalDate(item.serviceStartDate),
      serviceEndDate = new LocalDate(item.serviceEndDate),
      productId = "",
      productRatePlanChargeId = "",
      chargeName = item.chargeName,
      unitPrice = grossPrice,
    )
  }
}
