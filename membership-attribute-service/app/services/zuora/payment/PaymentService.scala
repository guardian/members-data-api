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
import com.gu.zuora.ZuoraSoapService
import com.gu.zuora.soap.models.Queries
import com.gu.zuora.soap.models.Queries.Account
import com.gu.zuora.soap.models.Queries.PaymentMethod._
import scalaz.std.option._
import scalaz.syntax.monad._
import scalaz.syntax.std.option._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class PaymentService(zuoraService: ZuoraSoapService)(implicit ec: ExecutionContext) extends SafeLogging {

  def paymentDetails(
      sub: Subscription,
      defaultMandateIdIfApplicable: Option[String] = None,
      catalog: Catalog,
  )(implicit logPrefix: LogPrefix): Future[PaymentDetails] = {
    val currency = sub.plan(catalog).chargesPrice.currencies.head
    // I am not convinced this function is very safe, hence the option
    val eventualMaybeLastPaymentDate = zuoraService
      .getPaymentSummary(sub.name, currency)
      .map(_.current.serviceStartDate.some)
      .recover { case _ => None }
    eventualMaybeLastPaymentDate.withLogging(s"lastPaymentDate for $sub")

    for {
      account <- zuoraService.getAccount(sub.accountId)
      eventualBills = getNextBill(sub.id, account, 15).withLogging(s"next bill for $sub")
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

  private def buildBankTransferPaymentMethod(defaultMandateIdIfApplicable: Option[String], m: Queries.PaymentMethod): Option[PaymentMethod] = {
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
      soapPaymentMethod: Queries.PaymentMethod,
  ): Option[PaymentMethod] =
    soapPaymentMethod.`type` match {
      case `CreditCard` | `CreditCardReferenceTransaction` =>
        val isReferenceTransaction = soapPaymentMethod.`type` == `CreditCardReferenceTransaction`
        def asInt(num: String) = Try(num.toInt).toOption
        val m = soapPaymentMethod
        val details =
          (m.creditCardNumber |@| m.creditCardExpirationMonth.flatMap(asInt) |@| m.creditCardExpirationYear.flatMap(asInt))(PaymentCardDetails)
        Some(PaymentCard(isReferenceTransaction, m.creditCardType, details, m.numConsecutiveFailures, m.paymentMethodStatus))
      case `BankTransfer` =>
        buildBankTransferPaymentMethod(defaultMandateIdIfApplicable, soapPaymentMethod)
      case `PayPal` =>
        Some(PayPalMethod(soapPaymentMethod.payPalEmail.get, soapPaymentMethod.numConsecutiveFailures, soapPaymentMethod.paymentMethodStatus))
      case _ => None
    }

  private def getNextBill(subId: Id, account: Account, numberOfBills: Int)(implicit logPrefix: LogPrefix): Future[List[Bill]] =
    for {
      previewInvoiceItems <- zuoraService.previewInvoices(subId, numberOfBills)
    } yield for {
      billingSched <- BillingSchedule.fromPreviewInvoiceItems(previewInvoiceItems).toList
      bill <- billingSched
        .withCreditBalanceApplied(account.creditBalance)
        .invoices
        .list
        .toList
    } yield bill

  def getPaymentMethod(maybePaymentMethodId: Option[String], defaultMandateIdIfApplicable: Option[String] = None)(implicit
      logPrefix: LogPrefix,
  ): Future[Option[PaymentMethod]] =
    (for {
      paymentMethodId <- maybePaymentMethodId
    } yield for {
      soapPaymentMethod <- zuoraService.getPaymentMethod(paymentMethodId).withLogging(s"get payment method for $maybePaymentMethodId")
    } yield buildPaymentMethod(defaultMandateIdIfApplicable, soapPaymentMethod))
      .getOrElse(Future.successful(None))

}
