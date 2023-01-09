package controllers

import actions._
import com.gu.memsub
import com.gu.memsub.Subscription.Name
import services.PaymentFailureAlerter._
import services._
import com.gu.memsub._
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
import com.gu.zuora.api.RegionalStripeGateways
import com.gu.zuora.rest.ZuoraRestService
import com.gu.zuora.rest.ZuoraRestService.PaymentMethodId
import com.typesafe.scalalogging.LazyLogging
import components.TouchpointComponents
import controllers.PaymentDetailMapper.paymentDetailsForSub
import loghandling.DeprecatedRequestLogger
import models.AccessScope.{completeReadSelf, readSelf, updateSelf}
import models.AccountDetails._
import models.ApiErrors._
import models._
import monitoring.CreateMetrics
import org.joda.time.LocalDate
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.{Format, Json}
import play.api.mvc._
import scalaz._
import scalaz.std.scalaFuture._
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
      messageSuffix: String,
  )(subscriptions: List[Subscription[P]]): Either[String, Subscription[P]] = subscriptionNameOption match {
    case Some(subName) => subscriptions.find(_.name == subName).toRight(s"$subName was not a subscription for $messageSuffix")
    case None => subscriptions.headOption.toRight(s"No current subscriptions for $messageSuffix")
  }

  def annotateFailableFuture[SuccessValue](failableFuture: Future[SuccessValue], action: String)(implicit
      executionContext: ExecutionContext,
  ): Future[Either[String, SuccessValue]] =
    failableFuture.map(Right(_)).recover { case exception =>
      Left(s"failed to $action. Exception : $exception")
    }

}

case class CancellationEffectiveDate(cancellationEffectiveDate: String)
object CancellationEffectiveDate {
  implicit val cancellationEffectiveDateFormat: Format[CancellationEffectiveDate] = Json.format[CancellationEffectiveDate]
}

class AccountController(
    commonActions: CommonActions,
    override val controllerComponents: ControllerComponents,
    contributionsStoreDatabaseService: ContributionsStoreDatabaseService,
    createMetrics: CreateMetrics,
) extends BaseController
    with LazyLogging {
  import AccountHelpers._
  import commonActions._
  implicit val executionContext: ExecutionContext = controllerComponents.executionContext

  val metrics = createMetrics.forService(classOf[AccountController])

  private def CancelError(details: String, code: Int): ApiError = ApiError("Failed to cancel subscription", details, code)

  def cancelSubscription[P <: SubscriptionPlan.AnyPlan: SubPlanReads](subscriptionName: memsub.Subscription.Name): Action[AnyContent] =
    AuthAndBackendViaAuthLibAction(requiredScopes = List(readSelf, updateSelf)).async { implicit request =>
      metrics.measureDuration("POST /user-attributes/me/cancel/:subscriptionName") {
        val tp = request.touchpoint
        val cancelForm = Form {
          single("reason" -> nonEmptyText)
        }
        // transforming to Option here because type of failure is no longer relevant at this point
        val maybeUserId = request.user.toOption.map(_.identityId)

        def handleInputBody(cancelForm: Form[String]): Future[Either[ApiError, String]] = Future.successful {
          cancelForm
            .bindFromRequest()
            .value
            .map { cancellationReason =>
              Right(cancellationReason)
            }
            .getOrElse {
              logger.warn("No reason for cancellation was submitted with the request.")
              Left(badRequest("Malformed request. Expected a valid reason for cancellation."))
            }
        }

        /** If user has multiple subscriptions within the same billing account, then disabling auto-pay on the account would stop collecting payments
          * for all subscriptions including the non-cancelled ones. In this case debt would start to accumulate in the form of positive Zuora account
          * balance, and if at any point auto-pay is switched back on, then payment for the entire amount would be attempted.
          */
        def disableAutoPayOnlyIfAccountHasOneSubscription(
            accountId: memsub.Subscription.AccountId,
        ): EitherT[String, Future, Future[Either[String, Unit]]] = {
          EitherT(tp.subService.subscriptionsForAccountId[P](accountId)).map { currentSubscriptions =>
            if (currentSubscriptions.size <= 1)
              tp.zuoraRestService.disableAutoPay(accountId).map(_.toEither)
            else // do not disable auto pay
              Future.successful(Right({}))
          }
        }

        def executeCancellation(
            cancellationEffectiveDate: Option[LocalDate],
            reason: String,
            accountId: memsub.Subscription.AccountId,
            endOfTermDate: LocalDate,
        ): Future[Either[ApiError, Option[LocalDate]]] = {
          (for {
            _ <- disableAutoPayOnlyIfAccountHasOneSubscription(accountId).leftMap(message => s"Failed to disable AutoPay: $message")
            _ <- EitherT(tp.zuoraRestService.updateCancellationReason(subscriptionName, reason)).leftMap(message =>
              s"Failed to update cancellation reason: $message",
            )
            _ <- EitherT(tp.zuoraRestService.cancelSubscription(subscriptionName, endOfTermDate, cancellationEffectiveDate)).leftMap(message =>
              s"Failed to execute Zuora cancellation proper: $message",
            )
          } yield cancellationEffectiveDate).leftMap(CancelError(_, 500)).run.map(_.toEither)
        }

        (for {
          identityId <- EitherT.fromEither(Future.successful(maybeUserId.toRight(unauthorized)))
          cancellationReason <- EitherT.fromEither(handleInputBody(cancelForm))
          sfContact <- EitherT
            .fromEither(tp.contactRepo.get(identityId).map(_.toEither.flatMap(_.toRight(s"No Salesforce user: $identityId"))))
            .leftMap(CancelError(_, 404))
          sfSub <- EitherT
            .fromEither(
              tp.subService.current[P](sfContact).map(subs => subscriptionSelector(Some(subscriptionName), s"Salesforce user $sfContact")(subs)),
            )
            .leftMap(CancelError(_, 404))
          accountId <- EitherT.fromEither(
            Future.successful(
              if (sfSub.name == subscriptionName) Right(sfSub.accountId)
              else Left(CancelError(s"$subscriptionName does not belong to $identityId", 503)),
            ),
          )
          cancellationEffectiveDate <- tp.subService.decideCancellationEffectiveDate[P](subscriptionName).leftMap(CancelError(_, 500))
          _ <- EitherT.fromEither(executeCancellation(cancellationEffectiveDate, cancellationReason, accountId, sfSub.termEndDate))
          result = cancellationEffectiveDate.getOrElse("now").toString
        } yield result).run.map(_.toEither).map {
          case Left(apiError) =>
            SafeLogger.error(scrub"Failed to cancel subscription for user $maybeUserId because $apiError")
            apiError
          case Right(cancellationEffectiveDate) =>
            logger.info(s"Successfully cancelled subscription $subscriptionName owned by $maybeUserId")
            Ok(Json.toJson(CancellationEffectiveDate(cancellationEffectiveDate)))
        }
      }
    }

  private def getCancellationEffectiveDate[P <: SubscriptionPlan.AnyPlan: SubPlanReads](subscriptionName: memsub.Subscription.Name) =
    AuthAndBackendViaAuthLibAction(requiredScopes = List(readSelf)).async { implicit request =>
      metrics.measureDuration("GET /user-attributes/me/cancellation-date/:subscriptionName") {
        val tp = request.touchpoint
        val maybeUserId = request.user.map(_.identityId)

        (for {
          cancellationEffectiveDate <- tp.subService
            .decideCancellationEffectiveDate[P](subscriptionName)
            .leftMap(error => ApiError("Failed to determine effectiveCancellationDate", error, 500))
          result = cancellationEffectiveDate.getOrElse("now").toString
        } yield result).run.map(_.toEither).map {
          case Left(apiError) =>
            SafeLogger.error(scrub"Failed to determine effectiveCancellationDate for $maybeUserId and $subscriptionName because $apiError")
            apiError
          case Right(cancellationEffectiveDate) =>
            logger.info(
              s"Successfully determined cancellation effective date for $subscriptionName owned by $maybeUserId as $cancellationEffectiveDate",
            )
            Ok(Json.toJson(CancellationEffectiveDate(cancellationEffectiveDate)))
        }
      }
    }

  @Deprecated
  private def paymentDetails[P <: SubscriptionPlan.Paid: SubPlanReads, F <: SubscriptionPlan.Free: SubPlanReads](metricName: String) =
    AuthAndBackendViaAuthLibAction(requiredScopes = List(completeReadSelf)).async { implicit request =>
      metrics.measureDuration(metricName) {
        DeprecatedRequestLogger.logDeprecatedRequest(request)

        implicit val tp: TouchpointComponents = request.touchpoint

        def getPaymentMethod(id: PaymentMethodId) = tp.zuoraRestService.getPaymentMethod(id.get).map(_.toEither)

        // transforming to Option here because type of failure is no longer relevant at this point
        val maybeUserId = request.user.toOption.map(_.identityId)

        logger.info(s"Deprecated function called: Attempting to retrieve payment details for identity user: ${maybeUserId.mkString}")
        (for {
          user <- OptionEither.liftFutureEither(maybeUserId)
          contact <- OptionEither(tp.contactRepo.get(user))
          freeOrPaidSub <- OptionEither(
            tp.subService
              .either[F, P](contact)
              .map(_.leftMap(message => s"couldn't read sub from zuora for crmId ${contact.salesforceAccountId} due to $message")),
          ).map(_.toEither)
          sub: Subscription[AnyPlan] = freeOrPaidSub.fold(identity, identity)
          paymentDetails <- OptionEither.liftOption(tp.paymentService.paymentDetails(\/.fromEither(freeOrPaidSub)).map(Right(_)).recover { case x =>
            Left(s"error retrieving payment details for subscription: ${sub.name}. Reason: $x")
          })
          accountSummary <- OptionEither.liftOption(tp.zuoraRestService.getAccount(sub.accountId).map(_.toEither).recover { case x =>
            Left(s"error receiving account summary for subscription: ${sub.name} with account id ${sub.accountId}. Reason: $x")
          })
          stripeService = accountSummary.billToContact.country
            .map(RegionalStripeGateways.getGatewayForCountry)
            .flatMap(tp.stripeServicesByPaymentGateway.get)
            .getOrElse(tp.ukStripeService)
          alertText <- OptionEither.liftEitherOption(alertText(accountSummary, sub, getPaymentMethod))
          cancellationEffectiveDate <- OptionEither.liftOption(tp.zuoraRestService.getCancellationEffectiveDate(sub.name).map(_.toEither))
          isAutoRenew = sub.autoRenew
        } yield AccountDetails(
          contactId = contact.salesforceContactId,
          regNumber = contact.regNumber,
          email = accountSummary.billToContact.email,
          deliveryAddress = None,
          subscription = sub,
          paymentDetails = paymentDetails,
          billingCountry = accountSummary.billToContact.country,
          stripePublicKey = stripeService.publicKey,
          accountHasMissedRecentPayments = false,
          safeToUpdatePaymentMethod = true,
          isAutoRenew = isAutoRenew,
          alertText = alertText,
          accountId = accountSummary.id.get,
          cancellationEffectiveDate,
        ).toJson).run.run.map(_.toEither).map {
          case Right(Some(result)) =>
            logger.info(s"Successfully retrieved payment details result for identity user: ${maybeUserId.mkString}")
            Ok(result)
          case Right(None) =>
            logger.info(s"identity user doesn't exist in SF: ${maybeUserId.mkString}")
            Ok(Json.obj())
          case Left(message) =>
            logger.warn(s"Unable to retrieve payment details result for identity user ${maybeUserId.mkString} due to $message")
            InternalServerError("Failed to retrieve payment details due to an internal error")
        }
      }
    }

  private def productIsInstanceOfProductType(product: Product, requestedProductType: String) = {
    val requestedProductTypeIsContentSubscription: Boolean = requestedProductType == "ContentSubscription"
    product match {
      // this ordering prevents Weekly subs from coming back when Paper is requested (which is different from the type hierarchy where Weekly extends Paper)
      case _: Product.Weekly => requestedProductType == "Weekly" || requestedProductTypeIsContentSubscription
      case _: Product.Voucher => requestedProductType == "Voucher" || requestedProductType == "Paper" || requestedProductTypeIsContentSubscription
      case _: Product.DigitalVoucher =>
        requestedProductType == "DigitalVoucher" || requestedProductType == "Paper" || requestedProductTypeIsContentSubscription
      case _: Product.Delivery =>
        requestedProductType == "HomeDelivery" || requestedProductType == "Paper" || requestedProductTypeIsContentSubscription
      case _: Product.Contribution => requestedProductType == "Contribution"
      case _: Product.Membership => requestedProductType == "Membership"
      case _: Product.ZDigipack => requestedProductType == "Digipack" || requestedProductTypeIsContentSubscription
      case _ => requestedProductType == product.name // fallback
    }
  }

  def checkForGiftSubscription(
      userId: String,
      subscriptionService: SubscriptionService[Future],
      zuoraRestService: ZuoraRestService[Future],
      nonGiftSubs: List[ContactAndSubscription],
      contact: Contact,
  ): OptionT[FutureEither, List[ContactAndSubscription]] = {
    val giftSub = for {
      records <- ListT(EitherT(zuoraRestService.getGiftSubscriptionRecordsFromIdentityId(userId).map(_.map(IList(_)))))
      result <-
        if (records.isEmpty)
          ListEither.liftFutureEither[Subscription[AnyPlan]](Nil)
        else
          reuseAlreadyFetchedSubscriptionIfAvailable(records, nonGiftSubs, subscriptionService)
    } yield result
    val fullList = giftSub
      .map(sub => ContactAndSubscription(contact, sub, isGiftRedemption = true))
    OptionEither.liftOption(fullList.run.map(_ ++ IList.fromList(nonGiftSubs)).run.map(_.toEither)).map(_.toList)
  }

  def reuseAlreadyFetchedSubscriptionIfAvailable(
      giftRecords: List[ZuoraRestService.GiftSubscriptionsFromIdentityIdRecord],
      nonGiftSubs: List[ContactAndSubscription],
      subscriptionService: SubscriptionService[Future],
  ): ListT[FutureEither, Subscription[AnyPlan]] = ListEither.liftFutureList {
    val all = giftRecords.map { giftRecord =>
      val subscriptionName = Name(giftRecord.Name)
      // If the current user is both the gifter and the giftee we will have already retrieved their
      // subscription so we can reuse it and avoid a call to Zuora
      nonGiftSubs.find(_.subscription.name == subscriptionName) match {
        case Some(contactAndSubscription) => Future.successful(Some(contactAndSubscription.subscription))
        case _ =>
          subscriptionService
            .get[AnyPlan](subscriptionName, isActiveToday = false) // set isActiveToday to false so that we find digisub plans with a one time charge
      }
    }
    Future.sequence(all).map(_.flatten) // failures turn to None, and are logged, so just ignore them
  }

  def allCurrentSubscriptions(
      contactRepo: SimpleContactRepository,
      subService: SubscriptionService[Future],
      zuoraRestService: ZuoraRestService[Future],
  )(
      maybeUserId: Option[String],
      filter: OptionalSubscriptionsFilter,
  ): OptionT[OptionEither.FutureEither, List[ContactAndSubscription]] = {
    def nonGiftContactAndSubscriptionsFor(contact: Contact): OptionT[FutureEither, List[ContactAndSubscription]] = {
      val future = subService.current[SubscriptionPlan.AnyPlan](contact)
      OptionEither.liftEitherOption(future map {
        _ map { subscription =>
          ContactAndSubscription(contact, subscription, isGiftRedemption = false)
        }
      })
    }

    def applyFilter(filter: OptionalSubscriptionsFilter, contactAndSubscriptions: List[ContactAndSubscription]) = {
      filter match {
        case FilterBySubName(subscriptionName) =>
          contactAndSubscriptions.find(_.subscription.name == subscriptionName).toList
        case FilterByProductType(productType) =>
          contactAndSubscriptions.filter(contactAndSubscription =>
            productIsInstanceOfProductType(
              contactAndSubscription.subscription.plan.product,
              productType,
            ),
          )
        case NoFilter =>
          contactAndSubscriptions
      }
    }

    for {
      userId <- OptionEither.liftFutureEither(maybeUserId)
      contact <- OptionEither(contactRepo.get(userId))
      nonGiftContactAndSubscriptions <- nonGiftContactAndSubscriptionsFor(contact)
      contactAndSubscriptions <- checkForGiftSubscription(
        userId,
        subService,
        zuoraRestService,
        nonGiftContactAndSubscriptions,
        contact,
      )
      filtered = applyFilter(filter, contactAndSubscriptions)
    } yield filtered
  }

  def reminders: Action[AnyContent] =
    AuthAndBackendViaIdapiAction(Return401IfNotSignedInRecently).async { implicit request =>
      metrics.measureDuration("GET /user-attributes/me/reminders") {
        request.redirectAdvice.userId match {
          case Some(userId) =>
            contributionsStoreDatabaseService.getSupportReminders(userId).map {
              case Left(databaseError) =>
                log.error(databaseError)
                InternalServerError
              case Right(supportReminders) =>
                Ok(Json.toJson(supportReminders))
            }
          case None => Future.successful(InternalServerError)
        }
      }
    }

  def getAccountDetailsParallel(
      contactAndSubscription: ContactAndSubscription,
      freeOrPaidSub: Either[Subscription[SubscriptionPlan.Free], Subscription[SubscriptionPlan.Paid]],
  )(implicit
      tp: TouchpointComponents,
  ): ListT[FutureEither, (PaymentDetails, ZuoraRestService.AccountSummary, Option[String])] = {
    // Run all these api calls in parallel to improve response times
    val paymentDetailsFuture =
      paymentDetailsForSub(contactAndSubscription.isGiftRedemption, freeOrPaidSub, tp.paymentService)
        .map(Right(_))
        .recover { case x =>
          Left(s"error retrieving payment details for subscription: freeOrPaidSub.name. Reason: $x")
        }

    val accountSummaryFuture =
      tp.zuoraRestService
        .getAccount(contactAndSubscription.subscription.accountId)
        .map(_.toEither)
        .recover { case x =>
          Left(
            s"error receiving account summary for subscription: ${contactAndSubscription.subscription.name} " +
              s"with account id ${contactAndSubscription.subscription.accountId}. Reason: $x",
          )
        }

    val effectiveCancellationDateFuture =
      tp.zuoraRestService
        .getCancellationEffectiveDate(contactAndSubscription.subscription.name)
        .map(_.toEither)
        .recover { case x =>
          Left(
            s"Failed to fetch effective cancellation date: ${contactAndSubscription.subscription.name} " +
              s"with account id ${contactAndSubscription.subscription.accountId}. Reason: $x",
          )
        }

    for {
      paymentDetails <- ListEither.liftList(paymentDetailsFuture)
      accountSummary <- ListEither.liftList(accountSummaryFuture)
      effectiveCancellationDate <- ListEither.liftList(effectiveCancellationDateFuture)
    } yield (paymentDetails, accountSummary, effectiveCancellationDate)
  }

  def getAccountDetailsFromZuora(filter: OptionalSubscriptionsFilter, maybeUserId: Option[String])(implicit
      tp: TouchpointComponents,
  ): ListT[FutureEither, AccountDetails] = {
    def getPaymentMethod(id: PaymentMethodId): Future[Either[String, ZuoraRestService.PaymentMethodResponse]] =
      tp.zuoraRestService.getPaymentMethod(id.get).map(_.toEither)

    for {
      contactAndSubscription <- ListEither.fromOptionEither(
        allCurrentSubscriptions(tp.contactRepo, tp.subService, tp.zuoraRestService)(maybeUserId, filter),
      )
      freeOrPaidSub = contactAndSubscription.subscription.plan.charges match {
        case _: PaidChargeList => Right(contactAndSubscription.subscription.asInstanceOf[Subscription[SubscriptionPlan.Paid]])
        case _ => Left(contactAndSubscription.subscription.asInstanceOf[Subscription[SubscriptionPlan.Free]])
      }
      detailsResultsTuple <- getAccountDetailsParallel(contactAndSubscription, freeOrPaidSub)
      (paymentDetails, accountSummary, effectiveCancellationDate) = detailsResultsTuple
      stripeService = accountSummary.billToContact.country
        .map(RegionalStripeGateways.getGatewayForCountry)
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
      paymentDetails = paymentDetails,
      billingCountry = accountSummary.billToContact.country,
      stripePublicKey = stripeService.publicKey,
      accountHasMissedRecentPayments = freeOrPaidSub.isRight &&
        accountHasMissedPayments(contactAndSubscription.subscription.accountId, accountSummary.invoices, accountSummary.payments),
      safeToUpdatePaymentMethod = safeToAllowPaymentUpdate(contactAndSubscription.subscription.accountId, accountSummary.invoices),
      isAutoRenew = isAutoRenew,
      alertText = alertText,
      accountId = accountSummary.id.get,
      effectiveCancellationDate,
    )
  }

  def anyPaymentDetails(filter: OptionalSubscriptionsFilter, metricName: String): Action[AnyContent] =
    AuthAndBackendViaIdapiAction(Return401IfNotSignedInRecently).async { implicit request =>
      metrics.measureDuration(metricName) {
        implicit val tp: TouchpointComponents = request.touchpoint
        val maybeUserId = request.redirectAdvice.userId

        logger.info(s"Attempting to retrieve payment details for identity user: ${maybeUserId.mkString}")

        (for {
          fromZuora <- OptionEither.liftOption(metrics.measureDuration("accountDetailsFromZuora") {
            getAccountDetailsFromZuora(filter, maybeUserId).run.toEither
          })
          fromStripe <- GuardianPatronService.getGuardianPatronAccountDetails(maybeUserId)
        } yield (fromZuora.toList ++ fromStripe).map(_.toJson)).run.run
          .map(_.toEither)
          .map {
            case Right(subscriptionJSONs) =>
              logger.info(s"Successfully retrieved payment details result for identity user: ${maybeUserId.mkString}")
              Ok(Json.toJson(subscriptionJSONs.getOrElse(Nil)))
            case Left(message) =>
              logger.warn(s"Unable to retrieve payment details result for identity user ${maybeUserId.mkString} due to $message")
              InternalServerError("Failed to retrieve payment details due to an internal error")
          }
      }
    }

  def fetchCancelledSubscriptions(): Action[AnyContent] =
    AuthAndBackendViaIdapiAction(Return401IfNotSignedInRecently).async { implicit request =>
      metrics.measureDuration("GET /user-attributes/me/cancelled-subscriptions") {
        implicit val tp: TouchpointComponents = request.touchpoint
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
    }

  private def updateContributionAmount(subscriptionNameOption: Option[memsub.Subscription.Name]) =
    AuthAndBackendViaAuthLibAction(requiredScopes = List(readSelf, updateSelf)).async { implicit request =>
      metrics.measureDuration("POST /user-attributes/me/contribution-update-amount/:subscriptionName") {
        if (subscriptionNameOption.isEmpty) {
          DeprecatedRequestLogger.logDeprecatedRequest(request)
        }

        val tp = request.touchpoint
        // transforming to Option here because type of failure is no longer relevant at this point
        val maybeUserId = request.user.toOption.map(_.identityId)
        logger.info(s"Attempting to update contribution amount for ${maybeUserId.mkString}")
        (for {
          newPrice <- EitherT.fromEither(Future.successful(validateContributionAmountUpdateForm))
          user <- EitherT.fromEither(Future.successful(maybeUserId.toRight("no identity cookie for user")))
          sfUser <- EitherT.fromEither(tp.contactRepo.get(user).map(_.toEither.flatMap(_.toRight(s"no SF user $user"))))
          subscription <- EitherT.fromEither(
            tp.subService
              .current[SubscriptionPlan.Contributor](sfUser)
              .map(subs => subscriptionSelector(subscriptionNameOption, s"the sfUser $sfUser")(subs)),
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
              applyFromDate,
            ),
          ).leftMap(message => s"Error while updating contribution amount: $message")
        } yield result).run.map(_.toEither) map {
          case Left(message) =>
            SafeLogger.error(scrub"Failed to update payment amount for user ${maybeUserId.mkString}, due to: $message")
            InternalServerError(message)
          case Right(()) =>
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

  def cancelSpecificSub(subscriptionName: String): Action[AnyContent] =
    cancelSubscription[SubscriptionPlan.AnyPlan](memsub.Subscription.Name(subscriptionName))

  def decideCancellationEffectiveDate(subscriptionName: String): Action[AnyContent] =
    getCancellationEffectiveDate[SubscriptionPlan.AnyPlan](memsub.Subscription.Name(subscriptionName))

  def cancelledSubscriptions(): Action[AnyContent] = fetchCancelledSubscriptions()

  @Deprecated def contributionUpdateAmount: Action[AnyContent] = updateContributionAmount(None)

  def updateAmountForSpecificContribution(subscriptionName: String): Action[AnyContent] = updateContributionAmount(
    Some(memsub.Subscription.Name(subscriptionName)),
  )

  @Deprecated def membershipDetails: Action[AnyContent] =
    paymentDetails[SubscriptionPlan.PaidMember, SubscriptionPlan.FreeMember]("GET /user-attributes/me/mma-membership")

  @Deprecated def monthlyContributionDetails: Action[AnyContent] = {
    implicit val nothingReads = SubPlanReads.nothingReads
    paymentDetails[SubscriptionPlan.Contributor, Nothing]("GET /user-attributes/me/mma-monthlycontribution")
  }

  @Deprecated def digitalPackDetails: Action[AnyContent] = {
    implicit val nothingReads = SubPlanReads.nothingReads
    paymentDetails[SubscriptionPlan.Digipack, Nothing]("GET /user-attributes/me/mma-digitalpack")
  }

  @Deprecated def paperDetails: Action[AnyContent] = {
    implicit val nothingReads = SubPlanReads.nothingReads
    paymentDetails[SubscriptionPlan.PaperPlan, Nothing]("GET /user-attributes/me/mma-paper")
  }

  def allPaymentDetails(productType: Option[String]): Action[AnyContent] =
    anyPaymentDetails(
      productType.fold[OptionalSubscriptionsFilter](NoFilter)(FilterByProductType.apply),
      "GET /user-attributes/me/mma",
    )
  def paymentDetailsSpecificSub(subscriptionName: String): Action[AnyContent] =
    anyPaymentDetails(
      FilterBySubName(memsub.Subscription.Name(subscriptionName)),
      "GET /user-attributes/me/mma/:subscriptionName",
    )
}
