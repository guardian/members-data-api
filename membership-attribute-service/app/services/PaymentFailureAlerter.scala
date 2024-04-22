package services

import com.gu.memsub.Product
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.monitoring.SafeLogging
import com.gu.zuora.api.{RegionalStripeGateways, StripeAUMembershipGateway, StripeUKMembershipGateway}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import scalaz.syntax.std.boolean._
import services.zuora.rest.ZuoraRestService.{AccountObject, AccountSummary, Invoice, Payment, PaymentMethodId, PaymentMethodResponse}

import java.util.Locale
import scala.concurrent.{ExecutionContext, Future}

object PaymentFailureAlerter extends SafeLogging {

  private def accountObject(accountSummary: AccountSummary) =
    AccountObject(
      Id = accountSummary.id,
      Balance = accountSummary.balance,
      Currency = accountSummary.currency,
      DefaultPaymentMethodId = accountSummary.defaultPaymentMethod.map(_.id),
      PaymentGateway = accountSummary.billToContact.country.map(RegionalStripeGateways.getGatewayForCountry),
      LastInvoiceDate = latestUnpaidInvoiceDate(accountSummary.invoices),
    )

  def latestUnpaidInvoiceDate(invoices: List[Invoice]): Option[DateTime] = {
    implicit def latestFirstDateTimeOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isAfter _)

    val unpaidInvoices = invoices.filter(invoice => invoice.balance > 0 && invoice.status == "Posted")
    val latestUnpaidInvoice = unpaidInvoices.sortBy(invoice => invoice.invoiceDate).headOption

    latestUnpaidInvoice.map(_.invoiceDate)
  }

  def alertText(
      accountSummary: AccountSummary,
      subscription: Subscription[AnyPlan],
      paymentMethodGetter: PaymentMethodId => Future[Either[String, PaymentMethodResponse]],
  )(implicit ec: ExecutionContext): Future[Option[String]] = {

    def expectedAlertText: Future[Option[String]] = {
      val formatter = DateTimeFormat.forPattern("d MMMM yyyy").withLocale(Locale.ENGLISH)

      val maybePaymentMethodLatestDate: Future[Option[DateTime]] = accountSummary.defaultPaymentMethod.map(_.id) match {
        case Some(id) =>
          val paymentMethod: Future[Either[String, PaymentMethodResponse]] =
            paymentMethodGetter(id) fallbackTo Future.successful(Left("Failed to get payment method"))
          paymentMethod.map(_.map(_.lastTransactionDateTime).toOption)
        case None => Future.successful(None)
      }

      def getProductDescription(subscription: Subscription[SubscriptionPlan.AnyPlan]) = if (subscription.asMembership.isDefined) {
        s"${subscription.plan.productName} membership"
      } else if (subscription.asContribution.isDefined) {
        "contribution"
      } else {
        subscription.plan.productName
      }

      maybePaymentMethodLatestDate map { maybeDate: Option[DateTime] =>
        maybeDate map { latestDate: DateTime =>
          val productDescription = getProductDescription(subscription)

          logger.info(
            s"Logging an alert for identityId: ${accountSummary.identityId} accountId: ${accountSummary.id}. Payment failed on ${latestDate.toString(formatter)}",
          )

          s"Our attempt to take payment for your $productDescription failed on ${latestDate.toString(formatter)}."
        }
      }
    }

    alertAvailableFor(accountObject(accountSummary), subscription, paymentMethodGetter) flatMap { shouldShowAlert: Boolean =>
      expectedAlertText.map { someText => shouldShowAlert.option(someText).flatten }
    }
  }

  val nonAlertableProducts: List[Product] = List()

  def alertAvailableFor(
      account: AccountObject,
      subscription: Subscription[AnyPlan],
      paymentMethodGetter: PaymentMethodId => Future[Either[String, PaymentMethodResponse]],
  )(implicit ec: ExecutionContext): Future[Boolean] = {
    def isAlertableProduct = !nonAlertableProducts.contains(subscription.plan.product)

    def creditCard(paymentMethodResponse: PaymentMethodResponse) =
      paymentMethodResponse.paymentMethodType == "CreditCardReferenceTransaction" || paymentMethodResponse.paymentMethodType == "CreditCard"

    val stillFreshInDays = 27
    def recentEnough(lastInvoiceDateTime: DateTime) = lastInvoiceDateTime.plusDays(stillFreshInDays).isAfterNow
    val isActionablePaymentGateway = account.PaymentGateway.exists(gw => gw == StripeUKMembershipGateway || gw == StripeAUMembershipGateway)

    def hasFailureForCreditCardPaymentMethod(paymentMethodId: PaymentMethodId): Future[Either[String, Boolean]] = {
      val eventualPaymentMethod: Future[Either[String, PaymentMethodResponse]] = paymentMethodGetter(paymentMethodId)
      eventualPaymentMethod map { maybePaymentMethod: Either[String, PaymentMethodResponse] =>
        maybePaymentMethod.map { pm: PaymentMethodResponse =>
          creditCard(pm) && pm.numConsecutiveFailures > 0
        }
      }
    }

    val alertAvailable = for {
      invoiceDate: DateTime <- account.LastInvoiceDate
      paymentMethodId: PaymentMethodId <- account.DefaultPaymentMethodId
    } yield {
      if (
        isAlertableProduct &&
        account.Balance > 0 &&
        isActionablePaymentGateway &&
        !subscription.isCancelled &&
        recentEnough(invoiceDate)
      ) hasFailureForCreditCardPaymentMethod(paymentMethodId)
      else Future.successful(Right(false))
    }.map(_.getOrElse(false))

    alertAvailable.getOrElse(Future.successful(false))
  }

  // Ignore unpaid invoices which are less than a month old, because these should be automatically dealt with by the payment retry process
  def mostRecentPayableInvoicesOlderThanOneMonth(recentInvoices: List[Invoice]): List[Invoice] = {
    implicit def localDateOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isAfter _)
    val nonZeroInvoicesOlderThanOneMonth =
      recentInvoices.filter(invoice => invoice.invoiceDate.isBefore(DateTime.now().minusMonths(1)) && invoice.amount > 0)
    nonZeroInvoicesOlderThanOneMonth.sortBy(_.invoiceDate).take(2)
  }

  def accountHasMissedPayments(accountId: AccountId, recentInvoices: List[Invoice], recentPayments: List[Payment]): Boolean = {
    val paidInvoiceNumbers = recentPayments.filter(_.status == "Processed").flatMap(_.paidInvoices).map(_.invoiceNumber)
    val unpaidPayableInvoiceOlderThanOneMonth = mostRecentPayableInvoicesOlderThanOneMonth(recentInvoices) match {
      case Nil => false
      case invoices => !invoices.forall(invoice => paidInvoiceNumbers.contains(invoice.invoiceNumber))
    }
    logger.info(s"${accountId.get} | accountHasMissedPayments: ${unpaidPayableInvoiceOlderThanOneMonth}")
    unpaidPayableInvoiceOlderThanOneMonth
  }

  def safeToAllowPaymentUpdate(accountId: AccountId, recentInvoices: List[Invoice]): Boolean = {
    val result = !recentInvoices.exists(invoice => invoice.balance > 0 && invoice.invoiceDate.isBefore(DateTime.now.minusMonths(1)))
    logger.info(s"${accountId.get} | safeToAllowPaymentUpdate: ${result}")
    result
  }

}
