package controllers

import actions._
import com.gu.i18n.Currency
import com.gu.memsub
import com.gu.memsub.BillingPeriod.RecurringPeriod
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.monitoring.SafeLogging
import com.gu.salesforce.Contact
import components.TouchpointComponents
import loghandling.DeprecatedRequestLogger
import models.AccessScope.{completeReadSelf, readSelf, updateSelf}
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
import services._
import services.mail.Emails.{subscriptionCancelledEmail, updateAmountEmail}
import services.mail.SendEmail
import utils.SimpleEitherT
import utils.SimpleEitherT.SimpleEitherT

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
    sendEmail: SendEmail,
    createMetrics: CreateMetrics,
) extends BaseController
    with SafeLogging {

  import AccountHelpers._
  import commonActions._

  implicit val executionContext: ExecutionContext = controllerComponents.executionContext

  val metrics = createMetrics.forService(classOf[AccountController])

  private def CancelError(details: String, code: Int): ApiError = ApiError("Failed to cancel subscription", details, code)

  def extractCancellationReason(cancelForm: Form[String])(implicit request: play.api.mvc.Request[_]): Either[ApiError, String] =
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

  def cancelSubscription[P <: SubscriptionPlan.AnyPlan: SubPlanReads](subscriptionName: memsub.Subscription.Name): Action[AnyContent] =
    AuthorizeForScopes(requiredScopes = List(readSelf, updateSelf)).async { implicit request =>
      metrics.measureDuration("POST /user-attributes/me/cancel/:subscriptionName") {
        val services = request.touchpoint
        val cancelForm = Form {
          single("reason" -> nonEmptyText)
        }
        val identityId = request.user.identityId
        def flatten[T](future: Future[\/[String, Option[T]]], errorMessage: String): SimpleEitherT[T] =
          SimpleEitherT(future.map(_.toEither.flatMap(_.toRight(errorMessage))))

        (for {
          cancellationReason <- EitherT.fromEither(Future(extractCancellationReason(cancelForm)))
          contact <-
            flatten(
              services.contactRepository.get(identityId),
              s"No Salesforce user: $identityId",
            ).leftMap(CancelError(_, 404))
          subscription <- SimpleEitherT(
            services.subscriptionService
              .current[P](contact)
              .map(subs => subscriptionSelector(Some(subscriptionName), s"Salesforce user $contact")(subs)),
          ).leftMap(CancelError(_, 404))
          accountId <-
            (if (subscription.name == subscriptionName)
               SimpleEitherT.right(subscription.accountId)
             else
               SimpleEitherT.left(s"$subscriptionName does not belong to $identityId"))
              .leftMap(CancelError(_, 503))
          cancellationEffectiveDate <- services.subscriptionService.decideCancellationEffectiveDate[P](subscriptionName).leftMap(CancelError(_, 500))
          _ <- services.cancelSubscription(subscriptionName, cancellationEffectiveDate, cancellationReason, accountId, subscription.termEndDate)
          result = cancellationEffectiveDate.getOrElse("now").toString
          _ <- sendSubscriptionCancelledEmail(
            request.user.primaryEmailAddress,
            contact,
            subscription.plan,
            cancellationEffectiveDate,
          )
        } yield result).run.map(_.toEither).map {
          case Left(apiError) =>
            logger.error(scrub"Failed to cancel subscription for user $identityId because $apiError")
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
        val services = request.touchpoint
        val userId = request.user.identityId

        (for {
          cancellationEffectiveDate <- services.subscriptionService
            .decideCancellationEffectiveDate[P](subscriptionName)
            .leftMap(error => ApiError("Failed to determine effectiveCancellationDate", error, 500))
          result = cancellationEffectiveDate.getOrElse("now").toString
        } yield result).run.map(_.toEither).map {
          case Left(apiError) =>
            logger.error(scrub"Failed to determine effectiveCancellationDate for $userId and $subscriptionName because $apiError")
            apiError
          case Right(cancellationEffectiveDate) =>
            logger.info(
              s"Successfully determined cancellation effective date for $subscriptionName owned by $userId as $cancellationEffectiveDate",
            )
            Ok(Json.toJson(CancellationEffectiveDate(cancellationEffectiveDate)))
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
                logger.error(scrub"DBERROR in reminders: $databaseError")
                InternalServerError
              case Right(supportReminders) =>
                Ok(Json.toJson(supportReminders))
            }
          case None => Future.successful(InternalServerError)
        }
      }
    }

  def anyPaymentDetails(filter: OptionalSubscriptionsFilter, metricName: String): Action[AnyContent] =
    AuthorizeForRecentLoginAndScopes(Return401IfNotSignedInRecently, requiredScopes = List(completeReadSelf)).async { request =>
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
              contact <- OptionT(EitherT(tp.contactRepository.get(identityId)))
              subs <- OptionT(EitherT(tp.subscriptionService.recentlyCancelled(contact)).map(Option(_)))
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

        val services = request.touchpoint
        val userId = request.user.identityId
        val email = request.user.primaryEmailAddress
        logger.info(s"Attempting to update contribution amount for $userId")
        (for {
          newPrice <- SimpleEitherT.fromEither(validateContributionAmountUpdateForm(request))
          user <- SimpleEitherT.right(userId)
          contact <- SimpleEitherT.fromFutureOption(services.contactRepository.get(user), s"No SF user $user")
          subscription <- SimpleEitherT(
            services.subscriptionService
              .current[SubscriptionPlan.Contributor](contact)
              .map(subs => subscriptionSelector(subscriptionNameOption, s"the sfUser $contact")(subs)),
          )
          billingPeriod = subscription.plan.charges.billingPeriod.asInstanceOf[RecurringPeriod]
          applyFromDate = subscription.plan.chargedThrough.getOrElse(subscription.plan.start)
          currency = subscription.plan.charges.price.prices.head.currency
          currencyGlyph = currency.glyph
          oldPrice = subscription.plan.charges.price.prices.head.amount
          reasonForChange =
            s"User updated contribution via self-service MMA. Amount changed from $currencyGlyph$oldPrice to $currencyGlyph$newPrice effective from $applyFromDate"
          result <- SimpleEitherT(
            services.zuoraRestService.updateChargeAmount(
              subscription.name,
              subscription.plan.charges.subRatePlanChargeId,
              subscription.plan.id,
              newPrice.toDouble,
              reasonForChange,
              applyFromDate,
            ),
          ).leftMap(message => s"Error while updating contribution amount: $message")
          _ <- sendUpdateAmountEmail(newPrice, email, contact, currency, billingPeriod, applyFromDate)
        } yield result).run.map(_.toEither) map {
          case Left(message) =>
            logger.error(scrub"Failed to update payment amount for user $userId, due to: $message")
            InternalServerError(message)
          case Right(()) =>
            logger.info(s"Contribution amount updated for user $userId")
            Ok("Success")
        }
      }
    }

  private def sendUpdateAmountEmail(
      newPrice: BigDecimal,
      email: String,
      contact: Contact,
      currency: Currency,
      billingPeriod: RecurringPeriod,
      nextPaymentDate: LocalDate,
  ) = SimpleEitherT.right(sendEmail(updateAmountEmail(email, contact, newPrice, currency, billingPeriod, nextPaymentDate)))

  private def sendSubscriptionCancelledEmail(
      email: String,
      contact: Contact,
      plan: SubscriptionPlan.AnyPlan,
      cancellationEffectiveDate: Option[LocalDate],
  ) =
    SimpleEitherT
      .right(sendEmail(subscriptionCancelledEmail(email, contact, plan, cancellationEffectiveDate)))
      .leftMap(ApiError(_, "Email could not be put on the queue", 500))

  private[controllers] def validateContributionAmountUpdateForm(implicit request: Request[AnyContent]): Either[String, BigDecimal] = {
    val minAmount = 1
    for {
      amount <- Form(single("newPaymentAmount" -> bigDecimal(5, 2))).bindFromRequest().value.toRight("no new payment amount submitted with request")
      validAmount <- Either.cond(amount >= minAmount, amount, s"New payment amount '$amount' is too small")
    } yield validAmount
  }

  def cancelSpecificSub(subscriptionName: String): Action[AnyContent] =
    cancelSubscription[SubscriptionPlan.AnyPlan](memsub.Subscription.Name(subscriptionName))

  def updateCancellationReason(subscriptionName: String): Action[AnyContent] =
    AuthorizeForScopes(requiredScopes = List(readSelf, updateSelf)).async { implicit request =>
      metrics.measureDuration("POST /user-attributes/me/update-cancellation-reason/:subscriptionName") {
        val subName = memsub.Subscription.Name(subscriptionName)
        val services = request.touchpoint
        val cancelForm = Form {
          single("reason" -> nonEmptyText)
        }
        val identityId = request.user.identityId
        val cancellationReasonEither = extractCancellationReason(cancelForm)

        cancellationReasonEither match {
          case Right(cancellationReason) =>
            services.zuoraRestService.updateCancellationReason(subName, cancellationReason).map {
              case -\/(error) =>
                logger.error(scrub"Failed to update cancellation reason for user $identityId because $error")
                InternalServerError(s"Failed to update cancellation reason with error: $error")
              case \/-(_) =>
                logger.info(s"Successfully updated cancellation reason for subscription $subscriptionName owned by $identityId")
                NoContent
            }
          case Left(apiError) =>
            Future.successful(BadRequest(Json.toJson(apiError)))
        }
      }
    }

  def decideCancellationEffectiveDate(subscriptionName: String): Action[AnyContent] =
    getCancellationEffectiveDate[SubscriptionPlan.AnyPlan](memsub.Subscription.Name(subscriptionName))

  def cancelledSubscriptions(): Action[AnyContent] = fetchCancelledSubscriptions()

  @Deprecated def contributionUpdateAmount: Action[AnyContent] = updateContributionAmount(None)

  def updateAmountForSpecificContribution(subscriptionName: String): Action[AnyContent] = updateContributionAmount(
    Some(memsub.Subscription.Name(subscriptionName)),
  )

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
