package controllers

import actions._
import com.gu.memsub
import services.PaymentFailureAlerter._
import com.gu.memsub._
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.subsv2.services.SubscriptionService
import com.gu.memsub.subsv2.{PaidChargeList, PaidSubscriptionPlan, Subscription, SubscriptionPlan}
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import com.gu.salesforce.SimpleContactRepository
import com.gu.services.model.PaymentDetails
import com.gu.stripe.{Stripe, StripeService}
import com.gu.zuora.api.RegionalStripeGateways
import com.gu.zuora.rest.ZuoraRestService.PaymentMethodId
import com.typesafe.scalalogging.LazyLogging
import components.TouchpointComponents
import loghandling.DeprecatedRequestLogger
import models.AccountDetails._
import models.ApiErrors._
import models.{AccountDetails, ApiError, ContactAndSubscription, DeliveryAddress}
import org.joda.time.{LocalDate, LocalTime}
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.Json
import play.api.mvc.{BaseController, ControllerComponents}
import scalaz.std.option._
import scalaz.std.scalaFuture._
import scalaz.syntax.monad._
import scalaz.syntax.std.option._
import scalaz.syntax.traverse._
import scalaz.{-\/, EitherT, OptionT, \/, \/-}
import utils.{ListEither, OptionEither}

import scala.concurrent.{ExecutionContext, Future}

object AccountHelpers {

  sealed trait OptionalSubscriptionsFilter
  case class FilterBySubName(subscriptionName: memsub.Subscription.Name) extends OptionalSubscriptionsFilter
  case class FilterByProductType(productType: String) extends OptionalSubscriptionsFilter
  case object NoFilter extends OptionalSubscriptionsFilter

  def subscriptionSelector[P <: SubscriptionPlan.AnyPlan](
    subscriptionNameOption: Option[memsub.Subscription.Name],
    messageSuffix: String
  )(subscriptions: List[Subscription[P]]): String \/ Subscription[P] = subscriptionNameOption match {
    case Some(subName) => subscriptions.find(_.name == subName) \/> s"$subName was not a subscription for $messageSuffix"
    case None => subscriptions.headOption \/> s"No current subscriptions for $messageSuffix"
  }

  def annotateFailableFuture[SuccessValue](failableFuture: Future[SuccessValue], action: String)(implicit executionContext: ExecutionContext): Future[String \/ SuccessValue] =
    failableFuture.map(\/.right).recover {
      case exception => \/.left(s"failed to $action. Exception : $exception")
    }

}

class AccountController(commonActions: CommonActions, override val controllerComponents: ControllerComponents) extends BaseController with LazyLogging {
  import AccountHelpers._
  import commonActions._
  implicit val executionContext: ExecutionContext = controllerComponents.executionContext

  case class CancellationEffectiveDate(
    subscriptionName: memsub.Subscription.Name,
    cancellationEffectiveDate: Option[LocalDate],
  )

  /**
   * fetched with /v1/subscription/{key}?charge-detail=current-segment which zeroes out all the non-active charges
   *
   * There are multiple scenarios
   *   - period between acquisition date and fulfilment date => None which indicates cancel now
   *     - usually contractEffectiveDate to customerAcceptanceDate, except in the case of Guardian Weekly+6for6
   *       where customerAcceptanceDate indicates start of GW proper invoiced period instead of start of 6for6
   *       invoiced period despite GW+6for6 being just a regular Subscription with multiple products.
   *     - free trial, or user choose start date of first issue in the future (lead time)
   *   - Subscription within invoiced period proper => Some(endOfLastInvoicePeriod)
   *   - free product => None which indicates cancel now
   *   - edge case of being on the first day of invoice period however bill run has not yet happened => ERROR
   *   - Today is after end of last invoice date and bill run has already completed => ERROR
   *
   * @return
   *   Right(None) indicates cancel now,
   *   Right(Some("yyyy-mm-dd")) indicates cancel at end of last invoiced period
   *   Left indicates error and MMA should not proceed with automatic cancelation
   */
  private def calculateEffectiveCancellationDate[P <: SubscriptionPlan.AnyPlan : SubPlanReads](
    identityId: String,
    subscriptionName: memsub.Subscription.Name,
    tp: TouchpointComponents
  ): EitherT[Future, String, CancellationEffectiveDate] = {
    EitherT(OptionT(tp.subService.get[P](subscriptionName, isActiveToday = true)).fold(
      zuoraSubscriptionWithCurrentSegment => {
        val paidPlans =
          zuoraSubscriptionWithCurrentSegment.plans.list.collect { case paidPlan: PaidSubscriptionPlan[_, _] => paidPlan }
        val now = LocalTime.now()
        val billRunHasAlreadyHappened = now.isAfter(LocalTime.parse("12:00"))

        paidPlans match {
          case paidPlan1 :: paidPlan2 :: _ => \/.left("Failed to determine specific single active paid rate plan charge")

          case paidPlan :: Nil => // single rate plan charge identified
            paidPlan.chargedThrough match {
              case Some(endOfLastInvoicePeriod) =>
                val endOfLastInvoiceDateIsBeforeToday = endOfLastInvoicePeriod.isBefore(LocalDate.now())
                if (endOfLastInvoiceDateIsBeforeToday && billRunHasAlreadyHappened)
                  \/.left("chargedThroughDate exists but seems out-of-date because bill run should have moved chargedThroughDate to next invoice period. Investigate ASAP!")
                else
                  \/.right(CancellationEffectiveDate(subscriptionName,Some(endOfLastInvoicePeriod)))
              case None =>
                if (paidPlan.start.equals(LocalDate.now()) && !billRunHasAlreadyHappened) // effectiveStartDate exists but not chargedThroughDate
                  \/.left(s"Invoiced period has started today, however Bill Run has not yet completed (it usually runs around 6am)")
                else
                  \/.left(s"Unknown reason for missing chargedThroughDate. Investigate ASAP!")
            }

          case Nil => \/.right(CancellationEffectiveDate(subscriptionName, Option.empty[LocalDate])) // free product so cancel now
        }
      },
      none = \/.right(CancellationEffectiveDate(subscriptionName, Option.empty[LocalDate]))) // we are within period between acquisition date and fulfilment date so cancel now (lead time / free trial)
    )
  }

  private def CancelError(details: String, code: Int): ApiError = ApiError("Failed to cancel subscription", details, code)

  def cancelSubscription[P <: SubscriptionPlan.AnyPlan : SubPlanReads](subscriptionName: memsub.Subscription.Name) = AuthAndBackendViaAuthLibAction.async { implicit request =>
    val tp = request.touchpoint
    val cancelForm = Form { single("reason" -> nonEmptyText) }
    val maybeUserId = request.user.map(_.id)

    def handleInputBody(cancelForm: Form[String]): Future[ApiError \/ String] = Future.successful {
      cancelForm.bindFromRequest().value.map { cancellationReason =>
        \/-(cancellationReason)
      }.getOrElse {
        logger.warn("No reason for cancellation was submitted with the request.")
        -\/(badRequest("Malformed request. Expected a valid reason for cancellation."))
      }
    }

    def executeCancellation(
      cancellationEffectiveDate: CancellationEffectiveDate,
      reason: String,
      accountId: memsub.Subscription.AccountId,
      endOfTermDate: LocalDate
    ): Future[ApiError \/ Unit] = {
      (for {
        _ <- EitherT(tp.zuoraRestService.disableAutoPay(accountId)).leftMap(message => s"Error while trying to disable AutoPay: $message")
        _ <- EitherT(tp.zuoraRestService.updateCancellationReason(cancellationEffectiveDate.subscriptionName, reason)).leftMap(message => s"Error while updating cancellation reason: $message")
        cancelResult <- EitherT(tp.zuoraRestService.cancelSubscription(cancellationEffectiveDate.subscriptionName, endOfTermDate, cancellationEffectiveDate.cancellationEffectiveDate)).leftMap(message => s"Error while cancelling subscription: $message")
      } yield cancelResult).leftMap { error =>
        logger.warn(s"Failed to execute zuora cancellation steps for user $maybeUserId, due to: $error")
        notFound
      }.run
    }

    logger.info(s"Attempting to cancel contribution for $maybeUserId")

    (for {
      identityId <- EitherT(Future.successful(maybeUserId \/> unauthorized))
      cancellationReason <- EitherT(handleInputBody(cancelForm))
      sfContact <- EitherT(tp.contactRepo.get(identityId).map(_.flatMap(_ \/> s"No Salesforce user: $identityId"))).leftMap(CancelError(_, 404))
      sfSub <- EitherT(tp.subService.current[P](sfContact).map(subscriptionSelector(Some(subscriptionName), s"Salesforce user $sfContact"))).leftMap(CancelError(_, 404))
      accountId <- EitherT(Future.successful(if (sfSub.name == subscriptionName) \/-(sfSub.accountId) else -\/(CancelError(s"$subscriptionName does not belong to $identityId", 503))))
      cancellationEffectiveDate <- calculateEffectiveCancellationDate[P](identityId, subscriptionName, tp).leftMap(CancelError(_, 500))
      cancellation <- EitherT(executeCancellation(cancellationEffectiveDate, cancellationReason, accountId, sfSub.termEndDate))
    } yield cancellation).run.map {
      case -\/(apiError) =>
        SafeLogger.error(scrub"Failed to cancel subscription for user $maybeUserId because $apiError")
        apiError
      case \/-(_) =>
        logger.info(s"Successfully cancelled subscription for user $maybeUserId")
        Ok
    }
  }

  private def getCancellationEffectiveDate[P <: SubscriptionPlan.AnyPlan : SubPlanReads](subscriptionName: memsub.Subscription.Name) = AuthAndBackendViaAuthLibAction.async { implicit request =>
    val tp = request.touchpoint
    val maybeUserId = request.user.map(_.id)

    (for {
      user <- EitherT(Future.successful(maybeUserId \/> unauthorized))
      cancellationEffectiveDate <- calculateEffectiveCancellationDate[P](user, subscriptionName, tp).leftMap(error => ApiError("Failed to determine effectiveCancellationDate", error, 500))
    } yield cancellationEffectiveDate.cancellationEffectiveDate).run.map {
      case -\/(apiError) =>
        SafeLogger.error(scrub"Failed to determine effectiveCancellationDate for $maybeUserId and $subscriptionName because $apiError")
        apiError
      case \/-(cancellationEffectiveDate) => Ok(cancellationEffectiveDate.getOrElse("now").toString)
    }
  }

  private def findStripeCustomer(customerId: String, likelyStripeService: StripeService)(implicit tp: TouchpointComponents): Future[Option[Stripe.Customer]] = {
    val alternativeStripeService = if (likelyStripeService == tp.ukStripeService) tp.auStripeService else tp.ukStripeService
    likelyStripeService.Customer.read(customerId).recoverWith {
      case _ => alternativeStripeService.Customer.read(customerId)
    } map(Option(_)) recover {
      case _ => None
    }
  }

  private def getUpToDatePaymentDetailsFromStripe(accountId: com.gu.memsub.Subscription.AccountId, paymentDetails: PaymentDetails)(implicit tp: TouchpointComponents): Future[PaymentDetails] = {
    paymentDetails.paymentMethod.map {
      case card: PaymentCard =>
        def liftFuture[A](m: Option[A]): OptionT[Future, A] = OptionT(Future.successful(m))
        (for {
          account <- tp.zuoraService.getAccount(accountId).liftM[OptionT]
          defaultPaymentMethodId <- liftFuture(account.defaultPaymentMethodId.map(_.trim).filter(_.nonEmpty))
          zuoraPaymentMethod <- tp.zuoraService.getPaymentMethod(defaultPaymentMethodId).liftM[OptionT]
          customerId <- liftFuture(zuoraPaymentMethod.secondTokenId.map(_.trim).filter(_.startsWith("cus_")))
          paymentGateway <- liftFuture(account.paymentGateway)
          stripeService <- liftFuture(tp.stripeServicesByPaymentGateway.get(paymentGateway))
          stripeCustomer <- OptionT(findStripeCustomer(customerId, stripeService))
          stripeCard = stripeCustomer.card
        } yield {
          // TODO consider broadcasting to a queue somewhere iff the payment method in Zuora is out of date compared to Stripe
          card.copy(
            cardType = Some(stripeCard.`type`),
            paymentCardDetails = Some(PaymentCardDetails(stripeCard.last4, stripeCard.exp_month, stripeCard.exp_year))
          )
        }).run
      case x => Future.successful(None) // not updated
    }.sequence.map { maybeUpdatedPaymentCard =>
      paymentDetails.copy(paymentMethod = maybeUpdatedPaymentCard.flatten orElse paymentDetails.paymentMethod)
    }
  }

  @Deprecated
  private def paymentDetails[P <: SubscriptionPlan.Paid : SubPlanReads, F <: SubscriptionPlan.Free : SubPlanReads] =
    AuthAndBackendViaAuthLibAction.async { implicit request =>
    DeprecatedRequestLogger.logDeprecatedRequest(request)

    implicit val tp = request.touchpoint
    def getPaymentMethod(id: PaymentMethodId) = tp.zuoraRestService.getPaymentMethod(id.get)
    val maybeUserId = request.user.map(_.id)

    logger.info(s"Attempting to retrieve payment details for identity user: ${maybeUserId.mkString}")
    (for {
      user <- OptionEither.liftFutureEither(maybeUserId)
      contact <- OptionEither(tp.contactRepo.get(user))
      freeOrPaidSub <- OptionEither(tp.subService.either[F, P](contact).map(_.leftMap(message => s"couldn't read sub from zuora for crmId ${contact.salesforceAccountId} due to $message")))
      sub = freeOrPaidSub.fold(identity, identity)
      paymentDetails <- OptionEither.liftOption(tp.paymentService.paymentDetails(freeOrPaidSub).map(\/.right).recover { case x => \/.left(s"error retrieving payment details for subscription: ${sub.name}. Reason: $x") })
      upToDatePaymentDetails <- OptionEither.liftOption(getUpToDatePaymentDetailsFromStripe(sub.accountId, paymentDetails).map(\/.right).recover { case x => \/.left(s"error getting up-to-date card details for payment method of account: ${sub.accountId}. Reason: $x") })
      accountSummary <- OptionEither.liftOption(tp.zuoraRestService.getAccount(sub.accountId).recover { case x => \/.left(s"error receiving account summary for subscription: ${sub.name} with account id ${sub.accountId}. Reason: $x") })
      stripeService = accountSummary.billToContact.country.map(RegionalStripeGateways.getGatewayForCountry).flatMap(tp.stripeServicesByPaymentGateway.get).getOrElse(tp.ukStripeService)
      alertText <- OptionEither.liftEitherOption(alertText(accountSummary, sub, getPaymentMethod))
      isAutoRenew = sub.autoRenew
    } yield AccountDetails(
      contactId = contact.salesforceContactId,
      regNumber = contact.regNumber,
      email = accountSummary.billToContact.email,
      deliveryAddress = None,
      subscription = sub,
      paymentDetails = upToDatePaymentDetails,
      billingCountry = accountSummary.billToContact.country,
      stripePublicKey = stripeService.publicKey,
      accountHasMissedRecentPayments = false,
      safeToUpdatePaymentMethod = true,
      isAutoRenew = isAutoRenew,
      alertText = alertText,
      accountId = accountSummary.id.get
    ).toJson).run.run.map {
      case \/-(Some(result)) =>
        logger.info(s"Successfully retrieved payment details result for identity user: ${maybeUserId.mkString}")
        Ok(result)
      case \/-(None) =>
        logger.info(s"identity user doesn't exist in SF: ${maybeUserId.mkString}")
        Ok(Json.obj())
      case -\/(message) =>
        logger.warn(s"Unable to retrieve payment details result for identity user ${maybeUserId.mkString} due to $message")
        InternalServerError("Failed to retrieve payment details due to an internal error")
    }
  }

  private def productIsInstanceOfProductType(product: Product, requestedProductType: String) = {
    val requestedProductTypeIsContentSubscription: Boolean = requestedProductType == "ContentSubscription"
    product match {
      // this ordering prevents Weekly subs from coming back when Paper is requested (which is different from the type hierarchy where Weekly extends Paper)
      case _: Product.Weekly => requestedProductType == "Weekly" || requestedProductTypeIsContentSubscription
      case _: Product.Voucher => requestedProductType == "Voucher" || requestedProductType == "Paper" || requestedProductTypeIsContentSubscription
      case _: Product.DigitalVoucher => requestedProductType == "DigitalVoucher" || requestedProductType == "Paper" || requestedProductTypeIsContentSubscription
      case _: Product.Delivery => requestedProductType == "HomeDelivery" || requestedProductType == "Paper" || requestedProductTypeIsContentSubscription
      case _: Product.Contribution => requestedProductType == "Contribution"
      case _: Product.Membership => requestedProductType == "Membership"
      case _: Product.ZDigipack => requestedProductType == "Digipack" || requestedProductTypeIsContentSubscription
      case _ => requestedProductType == product.name // fallback
    }
  }

  def allCurrentSubscriptions(
    contactRepo: SimpleContactRepository,
    subService: SubscriptionService[Future]
  )(
      maybeUserId: Option[String],
      filter: OptionalSubscriptionsFilter
  ): OptionT[OptionEither.FutureEither, List[ContactAndSubscription]] = for {
      user <- OptionEither.liftFutureEither(maybeUserId)
      contact <- OptionEither(contactRepo.get(user))
      contactAndSubscriptions <-
        OptionEither.liftEitherOption(
          subService.current[SubscriptionPlan.AnyPlan](contact) map {
            _ map { subscription =>
              ContactAndSubscription(contact, subscription)
            }
          }
        ) // TODO are we happy with an empty list in case of error ?!?!
      filteredIfApplicable = filter match {
        case FilterBySubName(subscriptionName) =>
          contactAndSubscriptions.find(_.subscription.name == subscriptionName).toList
        case FilterByProductType(productType) =>
          contactAndSubscriptions.filter(
            contactAndSubscription =>
              productIsInstanceOfProductType(
                contactAndSubscription.subscription.plan.product,
                productType
              )
          )
        case NoFilter =>
          contactAndSubscriptions
      }
    } yield filteredIfApplicable

  def anyPaymentDetails(filter: OptionalSubscriptionsFilter) = AuthAndBackendViaIdapiAction(Return401IfNotSignedInRecently).async { implicit request =>
    implicit val tp = request.touchpoint
    def getPaymentMethod(id: PaymentMethodId) = tp.zuoraRestService.getPaymentMethod(id.get)
    val maybeUserId = request.redirectAdvice.userId

    logger.info(s"Attempting to retrieve payment details for identity user: ${maybeUserId.mkString}")
    (for {
      contactAndSubscription <- ListEither.fromOptionEither(allCurrentSubscriptions(tp.contactRepo, tp.subService)(maybeUserId, filter))
      freeOrPaidSub = contactAndSubscription.subscription.plan.charges match {
        case _: PaidChargeList => \/.right(contactAndSubscription.subscription.asInstanceOf[Subscription[SubscriptionPlan.Paid]])
        case _ => \/.left(contactAndSubscription.subscription.asInstanceOf[Subscription[SubscriptionPlan.Free]])
      }
      paymentDetails <- ListEither.liftList(tp.paymentService.paymentDetails(freeOrPaidSub, defaultMandateIdIfApplicable = Some("")).map(\/.right).recover { case x => \/.left(s"error retrieving payment details for subscription: ${contactAndSubscription.subscription.name}. Reason: $x") })
      upToDatePaymentDetails <- ListEither.liftList(getUpToDatePaymentDetailsFromStripe(contactAndSubscription.subscription.accountId, paymentDetails).map(\/.right).recover { case x => \/.left(s"error getting up-to-date card details for payment method of account: ${contactAndSubscription.subscription.accountId}. Reason: $x") })
      accountSummary <- ListEither.liftList(tp.zuoraRestService.getAccount(contactAndSubscription.subscription.accountId).recover { case x => \/.left(s"error receiving account summary for subscription: ${contactAndSubscription.subscription.name} with account id ${contactAndSubscription.subscription.accountId}. Reason: $x") })
      stripeService = accountSummary.billToContact.country.map(RegionalStripeGateways.getGatewayForCountry).flatMap(tp.stripeServicesByPaymentGateway.get).getOrElse(tp.ukStripeService)
      alertText <- ListEither.liftEitherList(alertText(accountSummary, contactAndSubscription.subscription, getPaymentMethod))
      isAutoRenew = contactAndSubscription.subscription.autoRenew
    } yield AccountDetails(
      contactId = contactAndSubscription.contact.salesforceContactId,
      regNumber = None,
      email = accountSummary.billToContact.email,
      deliveryAddress = Some(DeliveryAddress.fromContact(contactAndSubscription.contact)),
      subscription = contactAndSubscription.subscription,
      paymentDetails = upToDatePaymentDetails,
      billingCountry = accountSummary.billToContact.country,
      stripePublicKey = stripeService.publicKey,
      accountHasMissedRecentPayments =
        freeOrPaidSub.isRight &&
        accountHasMissedPayments(contactAndSubscription.subscription.accountId, accountSummary.invoices, accountSummary.payments),
      safeToUpdatePaymentMethod = safeToAllowPaymentUpdate(contactAndSubscription.subscription.accountId, accountSummary.invoices),
      isAutoRenew = isAutoRenew,
      alertText = alertText,
      accountId = accountSummary.id.get
    ).toJson).run.run.map {
      case \/-(subscriptionJSONs) =>
        logger.info(s"Successfully retrieved payment details result for identity user: ${maybeUserId.mkString}")
        Ok(Json.toJson(subscriptionJSONs))
      case -\/(message) =>
        logger.warn(s"Unable to retrieve payment details result for identity user ${maybeUserId.mkString} due to $message")
        InternalServerError("Failed to retrieve payment details due to an internal error")
    }
  }

  private def updateContributionAmount(subscriptionNameOption: Option[memsub.Subscription.Name]) = AuthAndBackendViaAuthLibAction.async { implicit request =>
    if(subscriptionNameOption.isEmpty){
      DeprecatedRequestLogger.logDeprecatedRequest(request)
    }

    val updateForm = Form { single("newPaymentAmount" -> bigDecimal(5, 2)) }
    val tp = request.touchpoint
    val maybeUserId = request.user.map(_.id)
    logger.info(s"Attempting to update contribution amount for ${maybeUserId.mkString}")
    (for {
      newPrice <- EitherT(Future.successful(updateForm.bindFromRequest().value \/> "no new payment amount submitted with request"))
      user <- EitherT(Future.successful(maybeUserId \/> "no identity cookie for user"))
      sfUser <- EitherT(tp.contactRepo.get(user).map(_.flatMap(_ \/> s"no SF user $user")))
      subscription <- EitherT(tp.subService.current[SubscriptionPlan.Contributor](sfUser).map(subscriptionSelector(subscriptionNameOption, s"the sfUser $sfUser")))
      applyFromDate = subscription.plan.chargedThrough.getOrElse(subscription.plan.start)
      currencyGlyph = subscription.plan.charges.price.prices.head.currency.glyph
      oldPrice = subscription.plan.charges.price.prices.head.amount
      reasonForChange = s"User updated contribution via self-service MMA. Amount changed from $currencyGlyph$oldPrice to $currencyGlyph$newPrice effective from $applyFromDate"
      result <- EitherT(tp.zuoraRestService.updateChargeAmount(subscription.name, subscription.plan.charges.subRatePlanChargeId, subscription.plan.id, newPrice.toDouble, reasonForChange, applyFromDate)).leftMap(message => s"Error while updating contribution amount: $message")
    } yield result).run map { _ match {
      case -\/(message) =>
        SafeLogger.error(scrub"Failed to update payment amount for user ${maybeUserId.mkString}, due to: $message")
        InternalServerError(message)
      case \/-(()) =>
        logger.info(s"Contribution amount updated for user ${maybeUserId.mkString}")
        Ok("Success")
    }
    }
  }

  def cancelSpecificSub(subscriptionName: String) = cancelSubscription[SubscriptionPlan.AnyPlan](memsub.Subscription.Name(subscriptionName))
  def decideCancellationEffectiveDate(subscriptionName: String) = getCancellationEffectiveDate[SubscriptionPlan.AnyPlan](memsub.Subscription.Name(subscriptionName))

  @Deprecated def contributionUpdateAmount = updateContributionAmount(None)
  def updateAmountForSpecificContribution(subscriptionName: String) = updateContributionAmount(Some(memsub.Subscription.Name(subscriptionName)))

  @Deprecated def membershipDetails = paymentDetails[SubscriptionPlan.PaidMember, SubscriptionPlan.FreeMember]
  @Deprecated def monthlyContributionDetails = paymentDetails[SubscriptionPlan.Contributor, Nothing]
  @Deprecated def digitalPackDetails = paymentDetails[SubscriptionPlan.Digipack, Nothing]
  @Deprecated def paperDetails = paymentDetails[SubscriptionPlan.PaperPlan, Nothing]
  def allPaymentDetails(productType: Option[String]) = anyPaymentDetails(productType.fold[OptionalSubscriptionsFilter](NoFilter)(FilterByProductType.apply))
  def paymentDetailsSpecificSub(subscriptionName: String) = anyPaymentDetails(FilterBySubName(memsub.Subscription.Name(subscriptionName)))

}
