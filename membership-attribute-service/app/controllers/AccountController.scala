package controllers

import actions._
import com.gu.memsub
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import com.gu.zuora.api.RegionalStripeGateways
import com.gu.zuora.rest.ZuoraRestService.PaymentMethodId
import com.typesafe.scalalogging.LazyLogging
import components.TouchpointComponents
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
import services.PaymentFailureAlerter._
import services._
import utils.SimpleEitherT.SimpleEitherT
import utils.{OptionTEither, SimpleEitherT}

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
    AuthorizeForScopes(requiredScopes = List(readSelf, updateSelf)).async { implicit request =>
      metrics.measureDuration("POST /user-attributes/me/cancel/:subscriptionName") {
        val tp = request.touchpoint
        val cancelForm = Form {
          single("reason" -> nonEmptyText)
        }
        val identityId = request.user.identityId

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
        ): SimpleEitherT[Unit] = {
          EitherT(tp.subService.subscriptionsForAccountId[P](accountId)).flatMap { currentSubscriptions =>
            if (currentSubscriptions.size <= 1)
              SimpleEitherT(tp.zuoraRestService.disableAutoPay(accountId).map(_.toEither))
            else // do not disable auto pay
              SimpleEitherT.right({})
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
          identityId <- EitherT.right(identityId)
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
            SafeLogger.error(scrub"Failed to cancel subscription for user $identityId because $apiError")
            apiError
          case Right(cancellationEffectiveDate) =>
            logger.info(s"Successfully cancelled subscription $subscriptionName owned by $identityId")
            Ok(Json.toJson(CancellationEffectiveDate(cancellationEffectiveDate)))
        }
      }
    }

  private def getCancellationEffectiveDate[P <: SubscriptionPlan.AnyPlan: SubPlanReads](subscriptionName: memsub.Subscription.Name) =
    AuthorizeForScopes(requiredScopes = List(readSelf)).async { implicit request =>
      metrics.measureDuration("GET /user-attributes/me/cancellation-date/:subscriptionName") {
        val tp = request.touchpoint
        val userId = request.user.identityId

        (for {
          cancellationEffectiveDate <- tp.subService
            .decideCancellationEffectiveDate[P](subscriptionName)
            .leftMap(error => ApiError("Failed to determine effectiveCancellationDate", error, 500))
          result = cancellationEffectiveDate.getOrElse("now").toString
        } yield result).run.map(_.toEither).map {
          case Left(apiError) =>
            SafeLogger.error(scrub"Failed to determine effectiveCancellationDate for $userId and $subscriptionName because $apiError")
            apiError
          case Right(cancellationEffectiveDate) =>
            logger.info(
              s"Successfully determined cancellation effective date for $subscriptionName owned by $userId as $cancellationEffectiveDate",
            )
            Ok(Json.toJson(CancellationEffectiveDate(cancellationEffectiveDate)))
        }
      }
    }

  @Deprecated
  private def paymentDetails[P <: SubscriptionPlan.Paid: SubPlanReads, F <: SubscriptionPlan.Free: SubPlanReads](metricName: String) =
    AuthorizeForScopes(requiredScopes = List(completeReadSelf)).async { implicit request =>
      metrics.measureDuration(metricName) {
        DeprecatedRequestLogger.logDeprecatedRequest(request)

        implicit val tp: TouchpointComponents = request.touchpoint

        def getPaymentMethod(id: PaymentMethodId) = tp.zuoraRestService.getPaymentMethod(id.get).map(_.toEither)

        val userId = request.user.identityId

        logger.info(s"Deprecated function called: Attempting to retrieve payment details for identity user: ${userId.mkString}")
        (for {
          user <- OptionTEither.some(userId)
          contact <- OptionTEither(tp.contactRepo.get(user))
          freeOrPaidSub <- OptionTEither(
            tp.subService
              .either[F, P](contact)
              .map(_.leftMap(message => s"couldn't read sub from zuora for crmId ${contact.salesforceAccountId} due to $message")),
          ).map(_.toEither)
          sub: Subscription[AnyPlan] = freeOrPaidSub.fold(identity, identity)
          paymentDetails <- OptionTEither.liftOption(tp.paymentService.paymentDetails(\/.fromEither(freeOrPaidSub)).map(Right(_)).recover { case x =>
            Left(s"error retrieving payment details for subscription: ${sub.name}. Reason: $x")
          })
          accountSummary <- OptionTEither.liftOption(tp.zuoraRestService.getAccount(sub.accountId).map(_.toEither).recover { case x =>
            Left(s"error receiving account summary for subscription: ${sub.name} with account id ${sub.accountId}. Reason: $x")
          })
          stripeService = accountSummary.billToContact.country
            .map(RegionalStripeGateways.getGatewayForCountry)
            .flatMap(tp.stripeServicesByPaymentGateway.get)
            .getOrElse(tp.ukStripeService)
          alertText <- OptionTEither.fromFuture(alertText(accountSummary, sub, getPaymentMethod))
          cancellationEffectiveDate <- OptionTEither.liftOption(tp.zuoraRestService.getCancellationEffectiveDate(sub.name).map(_.toEither))
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
            logger.info(s"Successfully retrieved payment details result for identity user: ${userId.mkString}")
            Ok(result)
          case Right(None) =>
            logger.info(s"identity user doesn't exist in SF: ${userId.mkString}")
            Ok(Json.obj())
          case Left(message) =>
            logger.warn(s"Unable to retrieve payment details result for identity user ${userId.mkString} due to $message")
            InternalServerError("Failed to retrieve payment details due to an internal error")
        }
      }
    }

  def reminders: Action[AnyContent] =
    AuthorizeForRecentLogin(Return401IfNotSignedInRecently, requiredScopes = List(completeReadSelf)).async { implicit request =>
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

  def anyPaymentDetails(filter: OptionalSubscriptionsFilter, metricName: String): Action[AnyContent] =
    AuthorizeForRecentLogin2(Return401IfNotSignedInRecently, requiredScopes = List(completeReadSelf)).async { request =>
      metrics.measureDuration(metricName) {
        val user = request.user
        val userId = user.identityId

        logger.info(s"Attempting to retrieve payment details for identity user: $userId")

        paymentDetails(userId, filter, request.touchpoint).toEither
          .map {
            case Right(subscriptionList) =>
              logger.info(s"Successfully retrieved payment details result for identity user: $userId")
              val response = ProductsResponse.from(user, subscriptionList)
              Ok(Json.toJson(response))
            case Left(message) =>
              logger.warn(s"Unable to retrieve payment details result for identity user $userId due to $message")
              InternalServerError("Failed to retrieve payment details due to an internal error")
          }
      }
    }

  private def paymentDetails(
      userId: String,
      filter: OptionalSubscriptionsFilter,
      touchpointComponents: TouchpointComponents,
  ): SimpleEitherT[List[AccountDetails]] = {
    for {
      fromZuora <- touchpointComponents.accountDetailsFromZuora.fetch(userId, filter)
      fromStripe <- touchpointComponents.guardianPatronService.getGuardianPatronAccountDetails(userId)
    } yield fromZuora ++ fromStripe
  }

  def fetchCancelledSubscriptions(): Action[AnyContent] =
    AuthorizeForRecentLogin(Return401IfNotSignedInRecently, requiredScopes = List(completeReadSelf)).async { implicit request =>
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
    AuthorizeForScopes(requiredScopes = List(readSelf, updateSelf)).async { implicit request =>
      metrics.measureDuration("POST /user-attributes/me/contribution-update-amount/:subscriptionName") {
        if (subscriptionNameOption.isEmpty) {
          DeprecatedRequestLogger.logDeprecatedRequest(request)
        }

        val tp = request.touchpoint
        val userId = request.user.identityId
        logger.info(s"Attempting to update contribution amount for ${userId}")
        (for {
          newPrice <- SimpleEitherT.fromEither(validateContributionAmountUpdateForm)
          user <- SimpleEitherT.right(userId)
          sfUser <- SimpleEitherT.fromFutureOption(tp.contactRepo.get(user), s"No SF user $user")
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
            SafeLogger.error(scrub"Failed to update payment amount for user $userId, due to: $message")
            InternalServerError(message)
          case Right(()) =>
            logger.info(s"Contribution amount updated for user $userId")
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
