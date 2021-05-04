package controllers

import actions._
import com.gu.memsub
import com.gu.memsub.Subscription.Name
import services._
import services.PaymentFailureAlerter._
import com.gu.memsub._
import com.gu.memsub.services.PaymentService
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.subsv2.services.SubscriptionService
import com.gu.memsub.subsv2.{PaidChargeList, Subscription, SubscriptionPlan}
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import com.gu.salesforce.{Contact, SimpleContactRepository}
import com.gu.services.model.PaymentDetails
import com.gu.services.model.PaymentDetails.PersonalPlan
import com.gu.stripe.{Stripe, StripeService}
import com.gu.zuora.api.RegionalStripeGateways
import com.gu.zuora.rest.ZuoraRestService
import com.gu.zuora.rest.ZuoraRestService.PaymentMethodId
import com.typesafe.scalalogging.LazyLogging
import components.TouchpointComponents
import controllers.PaymentDetailMapper.paymentDetailsForSub
import loghandling.DeprecatedRequestLogger
import models.AccountDetails._
import models.ApiErrors._
import models._
import org.joda.time.LocalDate
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.{Json, __}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents, Request}
import scalaz.std.option._
import scalaz.std.scalaFuture._
import scalaz.syntax.monad._
import scalaz.syntax.std.option._
import scalaz.syntax.traverse._
import scalaz.{-\/, EitherT, OptionT, \/, \/-}
import utils.OptionEither.FutureEither
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
    case None          => subscriptions.headOption \/> s"No current subscriptions for $messageSuffix"
  }

  def annotateFailableFuture[SuccessValue](failableFuture: Future[SuccessValue], action: String)(implicit
      executionContext: ExecutionContext
  ): Future[String \/ SuccessValue] =
    failableFuture.map(\/.right).recover { case exception =>
      \/.left(s"failed to $action. Exception : $exception")
    }

}

case class CancellationEffectiveDate(cancellationEffectiveDate: String)
object CancellationEffectiveDate {
  implicit val cancellationEffectiveDateFormat = Json.format[CancellationEffectiveDate]
}

class AccountController(commonActions: CommonActions, override val controllerComponents: ControllerComponents, contributionsStoreDatabaseService: ContributionsStoreDatabaseService)
    extends BaseController
    with LazyLogging {
  import AccountHelpers._
  import commonActions._
  implicit val executionContext: ExecutionContext = controllerComponents.executionContext

  private def CancelError(details: String, code: Int): ApiError = ApiError("Failed to cancel subscription", details, code)

  def cancelSubscription[P <: SubscriptionPlan.AnyPlan: SubPlanReads](subscriptionName: memsub.Subscription.Name) =
    AuthAndBackendViaAuthLibAction.async { implicit request =>
      val tp = request.touchpoint
      val cancelForm = Form { single("reason" -> nonEmptyText) }
      val maybeUserId = request.user.map(_.id)

      def handleInputBody(cancelForm: Form[String]): Future[ApiError \/ String] = Future.successful {
        cancelForm
          .bindFromRequest()
          .value
          .map { cancellationReason =>
            \/-(cancellationReason)
          }
          .getOrElse {
            logger.warn("No reason for cancellation was submitted with the request.")
            -\/(badRequest("Malformed request. Expected a valid reason for cancellation."))
          }
      }

      /**
       * If user has multiple subscriptions within the same billing account, then disabling auto-pay
       * on the account would stop collecting payments for all subscriptions including the non-cancelled ones.
       * In this case debt would start to accumulate in the form of positive Zuora account balance, and if at
       * any point auto-pay is switched back on, then payment for the entire amount would be attempted.
       */
      def disableAutoPayOnlyIfAccountHasOneSubscription(
        accountId: memsub.Subscription.AccountId
      ): EitherT[String, Future, Future[String \/ Unit]] = {
          EitherT(tp.subService.subscriptionsForAccountId[P](accountId)).map { currentSubscriptions =>
            if (currentSubscriptions.size <= 1)
              tp.zuoraRestService.disableAutoPay(accountId)
            else // do not disable auto pay
              Future.successful(\/.right({}))
          }
      }

      def executeCancellation(
          cancellationEffectiveDate: Option[LocalDate],
          reason: String,
          accountId: memsub.Subscription.AccountId,
          endOfTermDate: LocalDate
      ): Future[ApiError \/ Option[LocalDate]] = {
        (for {
          _ <- disableAutoPayOnlyIfAccountHasOneSubscription(accountId).leftMap(message => s"Failed to disable AutoPay: $message")
          _ <- EitherT(tp.zuoraRestService.updateCancellationReason(subscriptionName, reason)).leftMap(message =>
            s"Failed to update cancellation reason: $message"
          )
          _ <- EitherT(tp.zuoraRestService.cancelSubscription(subscriptionName, endOfTermDate, cancellationEffectiveDate)).leftMap(message =>
            s"Failed to execute Zuora cancellation proper: $message"
          )
        } yield cancellationEffectiveDate).leftMap(CancelError(_, 500)).run
      }

      (for {
        identityId <- EitherT(Future.successful(maybeUserId \/> unauthorized))
        cancellationReason <- EitherT(handleInputBody(cancelForm))
        sfContact <- EitherT(tp.contactRepo.get(identityId).map(_.flatMap(_ \/> s"No Salesforce user: $identityId"))).leftMap(CancelError(_, 404))
        sfSub <- EitherT(tp.subService.current[P](sfContact).map(subscriptionSelector(Some(subscriptionName), s"Salesforce user $sfContact")))
          .leftMap(CancelError(_, 404))
        accountId <- EitherT(
          Future.successful(
            if (sfSub.name == subscriptionName) \/-(sfSub.accountId)
            else -\/(CancelError(s"$subscriptionName does not belong to $identityId", 503))
          )
        )
        cancellationEffectiveDate <- tp.subService.decideCancellationEffectiveDate[P](subscriptionName).leftMap(CancelError(_, 500))
        _ <- EitherT(executeCancellation(cancellationEffectiveDate, cancellationReason, accountId, sfSub.termEndDate))
        result = cancellationEffectiveDate.getOrElse("now").toString
      } yield result).run.map {
        case -\/(apiError) =>
          SafeLogger.error(scrub"Failed to cancel subscription for user $maybeUserId because $apiError")
          apiError
        case \/-(cancellationEffectiveDate) =>
          logger.info(s"Successfully cancelled subscription $subscriptionName owned by $maybeUserId")
          Ok(Json.toJson(CancellationEffectiveDate(cancellationEffectiveDate)))
      }
    }

  private def getCancellationEffectiveDate[P <: SubscriptionPlan.AnyPlan: SubPlanReads](subscriptionName: memsub.Subscription.Name) =
    AuthAndBackendViaAuthLibAction.async { implicit request =>
      val tp = request.touchpoint
      val maybeUserId = request.user.map(_.id)

      (for {
        identityId <- EitherT(Future.successful(maybeUserId \/> unauthorized))
        cancellationEffectiveDate <- tp.subService
          .decideCancellationEffectiveDate[P](subscriptionName)
          .leftMap(error => ApiError("Failed to determine effectiveCancellationDate", error, 500))
        result = cancellationEffectiveDate.getOrElse("now").toString
      } yield result).run.map {
        case -\/(apiError) =>
          SafeLogger.error(scrub"Failed to determine effectiveCancellationDate for $maybeUserId and $subscriptionName because $apiError")
          apiError
        case \/-(cancellationEffectiveDate) =>
          logger.info(
            s"Successfully determined cancellation effective date for $subscriptionName owned by $maybeUserId as $cancellationEffectiveDate"
          )
          Ok(Json.toJson(CancellationEffectiveDate(cancellationEffectiveDate)))
      }
    }

  private def findStripeCustomer(customerId: String, likelyStripeService: StripeService)(implicit
      tp: TouchpointComponents
  ): Future[Option[Stripe.Customer]] = {
    val alternativeStripeService = if (likelyStripeService == tp.ukStripeService) tp.auStripeService else tp.ukStripeService
    likelyStripeService.Customer.read(customerId).recoverWith { case _ =>
      alternativeStripeService.Customer.read(customerId)
    } map (Option(_)) recover { case _ =>
      None
    }
  }

  private def getUpToDatePaymentDetailsFromStripe(accountId: com.gu.memsub.Subscription.AccountId, paymentDetails: PaymentDetails)(implicit
      tp: TouchpointComponents
  ): Future[PaymentDetails] = {
    paymentDetails.paymentMethod
      .map {
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
      }
      .sequence
      .map { maybeUpdatedPaymentCard =>
        paymentDetails.copy(paymentMethod = maybeUpdatedPaymentCard.flatten orElse paymentDetails.paymentMethod)
      }
  }

  @Deprecated
  private def paymentDetails[P <: SubscriptionPlan.Paid: SubPlanReads, F <: SubscriptionPlan.Free: SubPlanReads] =
    AuthAndBackendViaAuthLibAction.async { implicit request =>
      DeprecatedRequestLogger.logDeprecatedRequest(request)

      implicit val tp = request.touchpoint
      def getPaymentMethod(id: PaymentMethodId) = tp.zuoraRestService.getPaymentMethod(id.get)
      val maybeUserId = request.user.map(_.id)

      logger.info(s"Attempting to retrieve payment details for identity user: ${maybeUserId.mkString}")
      (for {
        user <- OptionEither.liftFutureEither(maybeUserId)
        contact <- OptionEither(tp.contactRepo.get(user))
        freeOrPaidSub <- OptionEither(
          tp.subService
            .either[F, P](contact)
            .map(_.leftMap(message => s"couldn't read sub from zuora for crmId ${contact.salesforceAccountId} due to $message"))
        )
        sub = freeOrPaidSub.fold(identity, identity)
        paymentDetails <- OptionEither.liftOption(tp.paymentService.paymentDetails(freeOrPaidSub).map(\/.right).recover { case x =>
          \/.left(s"error retrieving payment details for subscription: ${sub.name}. Reason: $x")
        })
        upToDatePaymentDetails <- OptionEither.liftOption(getUpToDatePaymentDetailsFromStripe(sub.accountId, paymentDetails).map(\/.right).recover {
          case x => \/.left(s"error getting up-to-date card details for payment method of account: ${sub.accountId}. Reason: $x")
        })
        accountSummary <- OptionEither.liftOption(tp.zuoraRestService.getAccount(sub.accountId).recover { case x =>
          \/.left(s"error receiving account summary for subscription: ${sub.name} with account id ${sub.accountId}. Reason: $x")
        })
        stripeService = accountSummary.billToContact.country
          .map(RegionalStripeGateways.getGatewayForCountry)
          .flatMap(tp.stripeServicesByPaymentGateway.get)
          .getOrElse(tp.ukStripeService)
        alertText <- OptionEither.liftEitherOption(alertText(accountSummary, sub, getPaymentMethod))
        cancellationEffectiveDate <- OptionEither.liftOption(tp.zuoraRestService.getCancellationEffectiveDate(sub.name))
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
        accountId = accountSummary.id.get,
        cancellationEffectiveDate
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
      case _: Product.Weekly  => requestedProductType == "Weekly" || requestedProductTypeIsContentSubscription
      case _: Product.Voucher => requestedProductType == "Voucher" || requestedProductType == "Paper" || requestedProductTypeIsContentSubscription
      case _: Product.DigitalVoucher =>
        requestedProductType == "DigitalVoucher" || requestedProductType == "Paper" || requestedProductTypeIsContentSubscription
      case _: Product.Delivery =>
        requestedProductType == "HomeDelivery" || requestedProductType == "Paper" || requestedProductTypeIsContentSubscription
      case _: Product.Contribution => requestedProductType == "Contribution"
      case _: Product.Membership   => requestedProductType == "Membership"
      case _: Product.ZDigipack    => requestedProductType == "Digipack" || requestedProductTypeIsContentSubscription
      case _                       => requestedProductType == product.name // fallback
    }
  }

  def checkForGiftSubscription(
    userId: String,
    subscriptionService: SubscriptionService[Future],
    zuoraRestService: ZuoraRestService[Future],
    nonGiftSubs: List[ContactAndSubscription],
    contact: Contact
  ): OptionT[FutureEither, List[ContactAndSubscription]] = {
    val giftSub = for {
      records <- OptionEither.liftOption(zuoraRestService.getGiftSubscriptionRecordsFromIdentityId(userId))
      result <- if (records.isEmpty)
        OptionEither.liftFutureEither[Subscription[AnyPlan]](None)
      else
        reuseAlreadyFetchedSubscriptionIfAvailable(records, nonGiftSubs, subscriptionService)
    } yield result
    val fullList = giftSub
      .map(sub => ContactAndSubscription(contact, sub, isGiftRedemption = true) :: nonGiftSubs)
      .getOrElse(nonGiftSubs)
    OptionEither.liftOption(fullList.run)
  }

  def reuseAlreadyFetchedSubscriptionIfAvailable(
    giftRecords:  List[ZuoraRestService.GiftSubscriptionsFromIdentityIdRecord],
    nonGiftSubs: List[ContactAndSubscription],
    subscriptionService: SubscriptionService[Future],
  ) = {
    val subscriptionName = Name(giftRecords.head.Name)
    OptionEither.liftFutureOption(
      // If the current user is both the gifter and the giftee we will have already retrieved their
      // subscription so we can reuse it and avoid a call to Zuora
      nonGiftSubs.find(_.subscription.name == subscriptionName) match {
        case Some(contactAndSubscription) => Future.successful(Some(contactAndSubscription.subscription))
        case _ => subscriptionService.get[AnyPlan](subscriptionName, isActiveToday = true)
      }
    )
  }

  def allCurrentSubscriptions(
    contactRepo: SimpleContactRepository,
    subService: SubscriptionService[Future],
    zuoraRestService: ZuoraRestService[Future]
  )(
      maybeUserId: Option[String],
      filter: OptionalSubscriptionsFilter
  ): OptionT[OptionEither.FutureEither, List[ContactAndSubscription]] = for {
    userId <- OptionEither.liftFutureEither(maybeUserId)
    contact <- OptionEither(contactRepo.get(userId))
    nonGiftContactAndSubscriptions <-
        OptionEither.liftEitherOption(
          subService.current[SubscriptionPlan.AnyPlan](contact) map {
            _ map { subscription =>
              ContactAndSubscription(contact, subscription, isGiftRedemption = false)
            }
          }
        )

    contactAndSubscriptions <- checkForGiftSubscription(
        userId,
        subService,
        zuoraRestService,
        nonGiftContactAndSubscriptions,
        contact
      )
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

  def reminders = AuthAndBackendViaIdapiAction(Return401IfNotSignedInRecently).async {
    implicit request =>
      request.redirectAdvice.userId match {
        case Some(userId) =>
          contributionsStoreDatabaseService.getSupportReminders(userId).map {
            case -\/(databaseError) =>
              log.error(databaseError)
              InternalServerError
            case \/-(supportReminders) =>
              Ok(Json.toJson(supportReminders))
          }
        case None => Future.successful(InternalServerError)
      }
  }

  def anyPaymentDetails(filter: OptionalSubscriptionsFilter) = AuthAndBackendViaIdapiAction(Return401IfNotSignedInRecently).async {
    implicit request =>
      implicit val tp = request.touchpoint
      def getPaymentMethod(id: PaymentMethodId) = tp.zuoraRestService.getPaymentMethod(id.get)
      val maybeUserId = request.redirectAdvice.userId

    logger.info(s"Attempting to retrieve payment details for identity user: ${maybeUserId.mkString}")
    (for {
      contactAndSubscription <- ListEither.fromOptionEither(allCurrentSubscriptions(tp.contactRepo, tp.subService, tp.zuoraRestService)(maybeUserId, filter))
      freeOrPaidSub = contactAndSubscription.subscription.plan.charges match {
        case _: PaidChargeList => \/.right(contactAndSubscription.subscription.asInstanceOf[Subscription[SubscriptionPlan.Paid]])
        case _ => \/.left(contactAndSubscription.subscription.asInstanceOf[Subscription[SubscriptionPlan.Free]])
      }
      paymentDetails <- ListEither.liftList(
        paymentDetailsForSub(contactAndSubscription.isGiftRedemption, freeOrPaidSub, tp.paymentService).map(\/.right).recover {
          case x => \/.left(s"error retrieving payment details for subscription: freeOrPaidSub.name. Reason: $x")
        }
      )
      upToDatePaymentDetails <- ListEither.liftList(
        getUpToDatePaymentDetailsFromStripe(contactAndSubscription.subscription.accountId, paymentDetails).map(\/.right).recover {
          case x => \/.left(s"error getting up-to-date card details for payment method of account: " +
            s"${contactAndSubscription.subscription.accountId}. Reason: $x")
        })
      accountSummary <- ListEither.liftList(
        tp.zuoraRestService.getAccount(contactAndSubscription.subscription.accountId).recover {
          case x => \/.left(s"error receiving account summary for subscription: ${contactAndSubscription.subscription.name} " +
            s"with account id ${contactAndSubscription.subscription.accountId}. Reason: $x")
        })
      effectiveCancellationDate <- ListEither.liftList(
        tp.zuoraRestService.getCancellationEffectiveDate(contactAndSubscription.subscription.name).recover {
          case x => \/.left(s"Failed to fetch effective cancellation date: ${contactAndSubscription.subscription.name} " +
            s"with account id ${contactAndSubscription.subscription.accountId}. Reason: $x")
        })
      stripeService = accountSummary.billToContact.country.map(RegionalStripeGateways.getGatewayForCountry)
        .flatMap(tp.stripeServicesByPaymentGateway.get)
        .getOrElse(tp.ukStripeService)
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
      accountId = accountSummary.id.get,
      effectiveCancellationDate
    ).toJson).run.run.map {
      case \/-(subscriptionJSONs) =>
        logger.info(s"Successfully retrieved payment details result for identity user: ${maybeUserId.mkString}")
        Ok(Json.toJson(subscriptionJSONs))
      case -\/(message) =>
        logger.warn(s"Unable to retrieve payment details result for identity user ${maybeUserId.mkString} due to $message")
        InternalServerError("Failed to retrieve payment details due to an internal error")
    }
  }

  def cancelledSubscriptionsImpl(): Action[AnyContent] =
    AuthAndBackendViaIdapiAction(Return401IfNotSignedInRecently).async { implicit request =>
      implicit val tp = request.touchpoint
      val emptyResponse = Ok("[]")
      request.redirectAdvice.userId match {
        case Some(identityId) =>
          (for {
            contact <- OptionT(EitherT(tp.contactRepo.get(identityId)))
            subs <- OptionT(EitherT(tp.subService.recentlyCancelled(contact)).map(Option(_)))
          } yield {
            Ok(Json.toJson(subs.map(CancelledSubscription(_))))
          }).getOrElse(emptyResponse).leftMap(_ => emptyResponse).merge // we discard errors as this is not critical endpoint

        case None => Future.successful(unauthorized)
      }
    }

  private def updateContributionAmount(subscriptionNameOption: Option[memsub.Subscription.Name]) = AuthAndBackendViaAuthLibAction.async {
    implicit request =>
      if (subscriptionNameOption.isEmpty) {
        DeprecatedRequestLogger.logDeprecatedRequest(request)
      }

      val tp = request.touchpoint
      val maybeUserId = request.user.map(_.id)
      logger.info(s"Attempting to update contribution amount for ${maybeUserId.mkString}")
      (for {
        newPrice <- EitherT.fromEither(Future.successful(validateContributionAmountUpdateForm))
        user <- EitherT(Future.successful(maybeUserId \/> "no identity cookie for user"))
        sfUser <- EitherT(tp.contactRepo.get(user).map(_.flatMap(_ \/> s"no SF user $user")))
        subscription <- EitherT(
          tp.subService.current[SubscriptionPlan.Contributor](sfUser).map(subscriptionSelector(subscriptionNameOption, s"the sfUser $sfUser"))
        )
        applyFromDate = subscription.plan.chargedThrough.getOrElse(subscription.plan.start)
        currencyGlyph = subscription.plan.charges.price.prices.head.currency.glyph
        oldPrice = subscription.plan.charges.price.prices.head.amount
        reasonForChange =
          s"User updated contribution via self-service MMA. Amount changed from $currencyGlyph$oldPrice to $currencyGlyph$newPrice effective from $applyFromDate"
        result <- EitherT(
          tp.zuoraRestService.updateChargeAmount(
            subscription.name,
            subscription.plan.charges.subRatePlanChargeId,
            subscription.plan.id,
            newPrice.toDouble,
            reasonForChange,
            applyFromDate
          )
        ).leftMap(message => s"Error while updating contribution amount: $message")
      } yield result).run map {
        _ match {
          case -\/(message) =>
            SafeLogger.error(scrub"Failed to update payment amount for user ${maybeUserId.mkString}, due to: $message")
            InternalServerError(message)
          case \/-(()) =>
            logger.info(s"Contribution amount updated for user ${maybeUserId.mkString}")
            Ok("Success")
        }
      }
  }

  private[controllers] def validateContributionAmountUpdateForm(implicit request: Request[AnyContent]): Either[String, BigDecimal] = {
    val minAmount = 1
    for {
      amount <- Form(single("newPaymentAmount" -> bigDecimal(5, 2))).bindFromRequest().value.toRight("no new payment amount submitted with request")
      validAmount <- Either.cond(amount >= minAmount, amount, s"New payment amount '$amount' is too small")
    } yield validAmount
  }

  def cancelSpecificSub(subscriptionName: String) = cancelSubscription[SubscriptionPlan.AnyPlan](memsub.Subscription.Name(subscriptionName))
  def decideCancellationEffectiveDate(subscriptionName: String) =
    getCancellationEffectiveDate[SubscriptionPlan.AnyPlan](memsub.Subscription.Name(subscriptionName))
  def cancelledSubscriptions() = cancelledSubscriptionsImpl()

  @Deprecated def contributionUpdateAmount = updateContributionAmount(None)
  def updateAmountForSpecificContribution(subscriptionName: String) = updateContributionAmount(Some(memsub.Subscription.Name(subscriptionName)))

  @Deprecated def membershipDetails = paymentDetails[SubscriptionPlan.PaidMember, SubscriptionPlan.FreeMember]
  @Deprecated def monthlyContributionDetails = paymentDetails[SubscriptionPlan.Contributor, Nothing]
  @Deprecated def digitalPackDetails = paymentDetails[SubscriptionPlan.Digipack, Nothing]
  @Deprecated def paperDetails = paymentDetails[SubscriptionPlan.PaperPlan, Nothing]
  def allPaymentDetails(productType: Option[String]) = anyPaymentDetails(
    productType.fold[OptionalSubscriptionsFilter](NoFilter)(FilterByProductType.apply)
  )
  def paymentDetailsSpecificSub(subscriptionName: String) = anyPaymentDetails(FilterBySubName(memsub.Subscription.Name(subscriptionName)))

}
