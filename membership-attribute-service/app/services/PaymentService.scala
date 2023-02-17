package services

import _root_.services.stripe.StripeService
import _root_.services.zuora.soap.ZuoraSoapService
import com.gu.memsub.Subscription._
import com.gu.memsub.subsv2.SubscriptionPlan.Contributor
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.memsub.{BillingSchedule, Subscription => _, _}
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger.Sanitizer
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.Payment
import com.gu.stripe.Stripe
import com.gu.stripe.Stripe.Customer
import com.gu.zuora.soap.models.Queries
import com.gu.zuora.soap.models.Queries.Account
import com.gu.zuora.soap.models.Queries.PaymentMethod._
import scalaz.std.option._
import scalaz.std.scalaFuture._
import scalaz.syntax.monad._
import scalaz.syntax.std.option._
import scalaz.{MonadTrans, OptionT, \/}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class PaymentService(zuoraService: ZuoraSoapService, planMap: Map[ProductRatePlanChargeId, Benefit])(implicit ec: ExecutionContext)
    extends api.PaymentService {

  implicit val monadTrans = MonadTrans[OptionT] // it's the only one we use here, really

  def paymentDetails(
      sub: Subscription[SubscriptionPlan.Free] \/ Subscription[SubscriptionPlan.Paid],
      defaultMandateIdIfApplicable: Option[String] = None,
  ): Future[PaymentDetails] =
    sub.fold(a => Future.successful(PaymentDetails(a)), paidPaymentDetails(defaultMandateIdIfApplicable))

  private def paidPaymentDetails(defaultMandateIdIfApplicable: Option[String])(sub: Subscription[SubscriptionPlan.Paid]): Future[PaymentDetails] = {
    val currency = sub.plan.charges.currencies.head
    // I am not convinced this function is very safe, hence the option
    val lastPaymentDate = zuoraService
      .getPaymentSummary(sub.name, currency)
      .map(_.current.serviceStartDate.some)
      .recover { case _ => None }
    lastPaymentDate.onComplete {
      case Failure(exception) =>
        val message = scrub"Failed to get last payment date for sub $sub"
        SafeLogger.error(message, exception)
      case Success(_) => SafeLogger.info(s"Successfully got payment details for $sub")
    }

    val nextPaymentDate = sub.plan match {
      case _: Contributor =>
        sub.plan.chargedThrough.getOrElse(
          sub.plan.start,
        ) // If the user has updated their contribution amount via MMA, there may be no charged through date
      case _ => sub.plan.chargedThrough.getOrElse(sub.acceptanceDate) // If the user hasn't paid us yet, there's no charged through date
    }
    val futureMaybePrice = sub.plan.product.name match {
      // There's no need to get the billing schedule for membership - since there are no promos etc.
      case "membership" => {
        Future.successful(sub.plan.charges.price.prices.headOption)
      }
      case _ => {
        val schedule = billingSchedule(sub.id, sub.accountId, 15).map { maybeSchedule =>
          maybeSchedule.map(schedule => Price(schedule.first.amount, currency))
        }
        schedule.onComplete {
          case Failure(exception) =>
            val message = scrub"Failed to get billing schedule for sub $sub"
            SafeLogger.error(message, exception)
          case Success(_) => SafeLogger.info(s"Successfully got billing schedule for $sub")
        }
        schedule
      }
    }
    (futureMaybePrice |@| getPaymentMethod(sub.accountId, defaultMandateIdIfApplicable) |@| lastPaymentDate) {
      case (maybePrice, paymentMethod, lpd) => {
        val nextPayment = maybePrice.map { price => Payment(price, nextPaymentDate) }
        PaymentDetails(sub, paymentMethod, nextPayment, lpd)
      }
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
  )(soapPaymentMethod: Queries.PaymentMethod): Option[PaymentMethod] =
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

  private def billingSchedule(subId: Id, accountFuture: Future[Account], numberOfBills: Int): Future[Option[BillingSchedule]] = {
    val finder: ProductRatePlanChargeId => Option[Benefit] = planMap.get
    val adapter: Seq[Queries.PreviewInvoiceItem] => Option[BillingSchedule] = BillingSchedule.fromPreviewInvoiceItems(finder)
    val scheduleFuture = zuoraService.previewInvoices(subId, numberOfBills).map(adapter)
    for {
      account <- accountFuture
      scheduleOpt <- scheduleFuture
    } yield {
      scheduleOpt.map(_.withCreditBalanceApplied(account.creditBalance))
    }
  }

  override def billingSchedule(subId: Id, numberOfBills: Int = 2): Future[Option[BillingSchedule]] = {
    for {
      sub <- zuoraService.getSubscription(subId)
      accountFuture = zuoraService.getAccount(AccountId(sub.accountId))
      schedule <- billingSchedule(subId, accountFuture, numberOfBills)
    } yield schedule
  }

  override def billingSchedule(subId: Id, accountId: AccountId, numberOfBills: Int): Future[Option[BillingSchedule]] =
    billingSchedule(subId, zuoraService.getAccount(accountId), numberOfBills)

  override def billingSchedule(subId: Id, account: Account, numberOfBills: Int): Future[Option[BillingSchedule]] =
    billingSchedule(subId, Future.successful(account), numberOfBills)

  override def getPaymentMethod(accountId: AccountId, defaultMandateIdIfApplicable: Option[String] = None): Future[Option[PaymentMethod]] =
    getPaymentMethodByAccountId(accountId).map(_.flatMap(buildPaymentMethod(defaultMandateIdIfApplicable)))

  override def getPaymentCard(accountId: AccountId): Future[Option[PaymentCard]] =
    getPaymentMethod(accountId).map(_.collect { case c: PaymentCard => c })

  @Deprecated
  override def setPaymentCardWithStripeToken(
      accountId: AccountId,
      stripeToken: String,
      stripeService: StripeService,
  ): Future[Option[PaymentCardUpdateResult]] =
    setPaymentCard(stripeService.createCustomer)(accountId, stripeToken, stripeService)

  override def setPaymentCardWithStripePaymentMethod(
      accountId: AccountId,
      stripePaymentMethodID: String,
      stripeService: StripeService,
  ): Future[Option[PaymentCardUpdateResult]] =
    setPaymentCard(stripeService.createCustomerWithStripePaymentMethod)(accountId, stripePaymentMethodID, stripeService)

  private def setPaymentCard(
      createCustomerFunction: String => Future[Customer],
  )(accountId: AccountId, stripeCardIdentifier: String, stripeService: StripeService): Future[Option[PaymentCardUpdateResult]] =
    (for {
      account <- zuoraService.getAccount(accountId).liftM
      customer <- createCustomerFunction(stripeCardIdentifier).liftM
      result <- zuoraService
        .createCreditCardPaymentMethod(accountId, customer, stripeService.paymentIntentsGateway, stripeService.invoiceTemplateOverride)
        .liftM
    } yield {
      CardUpdateSuccess(
        PaymentCard(
          isReferenceTransaction = true,
          cardType = Some(customer.card.`type`),
          paymentCardDetails = Some(PaymentCardDetails(customer.card.last4, customer.card.exp_month, customer.card.exp_year)),
        ),
      )
    }).run.recover { case error: Stripe.Error =>
      Some(CardUpdateFailure(error.`type`, error.message.getOrElse(""), error.code.getOrElse("unknown")))
    }

  private def getPaymentMethodByAccountId(accountId: AccountId): Future[Option[Queries.PaymentMethod]] = {
    def getAccount = {
      val account = zuoraService.getAccount(accountId)
      account.onComplete {
        case Failure(exception) =>
          val message = scrub"Failed to get account for account $accountId"
          SafeLogger.error(message, exception)
        case Success(_) => SafeLogger.info(s"Successfully got account for $accountId")
      }
      account
    }
    def getPaymentMethod(account: Account) = {
      val paymentMethod = getPaymentMethodByAccount(account)
      paymentMethod.onComplete {
        case Failure(exception) =>
          val message = scrub"Failed to get payment method for account $accountId"
          SafeLogger.error(message, exception)
        case Success(_) => SafeLogger.info(s"Successfully got payment method for $accountId")
      }
      paymentMethod
    }
    for {
      account <- getAccount
      paymentMethod <- getPaymentMethod(account)
    } yield paymentMethod
  }

  private def getPaymentMethodByAccount(account: Account): Future[Option[Queries.PaymentMethod]] =
    (for {
      paymentId <- OptionT(Future.successful(account.defaultPaymentMethodId))
      paymentMethod <- zuoraService.getPaymentMethod(paymentId).liftM
    } yield paymentMethod).run
}
