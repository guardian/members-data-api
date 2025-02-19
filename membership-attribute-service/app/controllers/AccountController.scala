package controllers

import actions._
import com.gu.i18n.Currency
import com.gu.memsub
import com.gu.memsub.BillingPeriod
import com.gu.memsub.BillingPeriod.RecurringPeriod
import com.gu.memsub.Product.Contribution
import com.gu.memsub.subsv2.{ProductType, Subscription}
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import com.gu.salesforce.Contact
import components.TouchpointComponents
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
  case class FilterBySubName(subscriptionNumber: memsub.Subscription.SubscriptionNumber) extends OptionalSubscriptionsFilter
  case class FilterByProductType(productType: String) extends OptionalSubscriptionsFilter
  case object NoFilter extends OptionalSubscriptionsFilter

  def subscriptionSelector(
      subscriptionNumber: memsub.Subscription.SubscriptionNumber,
      messageSuffix: String,
      subscriptions: List[Subscription],
  ): Either[String, Subscription] =
    subscriptions.find(_.subscriptionNumber == subscriptionNumber).toRight(s"$subscriptionNumber was not a subscription for $messageSuffix")

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

  def extractCancellationReason(cancelForm: Form[String])(implicit request: play.api.mvc.Request[_], logPrefix: LogPrefix): Option[String] =
    cancelForm
      .bindFromRequest()
      .value

  def cancelSubscription(subscriptionNameString: String): Action[AnyContent] =
    AuthorizeForScopes(requiredScopes = List(readSelf, updateSelf)).async { implicit request =>
      import request.logPrefix
      metrics.measureDuration("POST /user-attributes/me/cancel/:subscriptionName") {
        val services = request.touchpoint
        val subscriptionNumber = memsub.Subscription.SubscriptionNumber(subscriptionNameString)
        val cancelForm = Form {
          single("reason" -> nonEmptyText)
        }
        val identityId = request.user.identityId
        def flatten[T](future: Future[\/[String, Option[T]]], errorMessage: String): SimpleEitherT[T] =
          SimpleEitherT(future.map(_.toEither.flatMap(_.toRight(errorMessage))))

        (for {
          contact <-
            flatten(
              services.contactRepository.get(identityId),
              s"No Salesforce user: $identityId",
            ).leftMap(CancelError(_, 404))
          subscription <- SimpleEitherT(
            services.subscriptionService
              .current(contact)
              .map(subs => subscriptionSelector(subscriptionNumber, s"Salesforce user $contact", subs)),
          ).leftMap(CancelError(_, 404))
          accountId <-
            (if (subscription.subscriptionNumber == subscriptionNumber)
               SimpleEitherT.right(subscription.accountId)
             else
               SimpleEitherT.left(s"$subscriptionNumber does not belong to $identityId"))
              .leftMap(CancelError(_, 503))
          cancellationEffectiveDate <- services.subscriptionService.decideCancellationEffectiveDate(subscriptionNumber).leftMap(CancelError(_, 500))
          cancellationReason = extractCancellationReason(cancelForm)
          _ <- services.cancelSubscription.cancel(
            subscriptionNumber,
            cancellationEffectiveDate,
            cancellationReason,
            accountId,
            subscription.termEndDate,
          )
          result = cancellationEffectiveDate.getOrElse("now").toString
          catalog <- EitherT.rightT(services.futureCatalog)
          _ <- sendSubscriptionCancelledEmail(
            request.user.primaryEmailAddress,
            contact,
            subscription.plan(catalog).productType(catalog),
            cancellationEffectiveDate,
          )
        } yield result).run.map(_.toEither).map {
          case Left(apiError) =>
            logger.error(scrub"Failed to cancel subscription for user $identityId because $apiError")
            apiError
          case Right(cancellationEffectiveDate) =>
            logger.info(s"Successfully cancelled subscription $subscriptionNumber owned by $identityId")
            Ok(Json.toJson(CancellationEffectiveDate(cancellationEffectiveDate)))
        }
      }
    }

  def getCancellationEffectiveDate(subscriptionName: String): Action[AnyContent] =
    AuthorizeForScopes(requiredScopes = List(readSelf)).async { implicit request =>
      import request.logPrefix
      metrics.measureDuration("GET /user-attributes/me/cancellation-date/:subscriptionName") {
        val services = request.touchpoint
        val userId = request.user.identityId

        (for {
          cancellationEffectiveDate <- services.subscriptionService
            .decideCancellationEffectiveDate(memsub.Subscription.SubscriptionNumber(subscriptionName))
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
      import request.logPrefix
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
      import request.logPrefix
      metrics.measureDuration(metricName) {
        val user = request.user
        val userId = user.identityId

        logger.info(s"Attempting to retrieve payment details for identity user: $userId")

        for {
          catalog <- request.touchpoint.futureCatalog
          result <- paymentDetails(userId, filter, request.touchpoint).toEither
        } yield result match {
          case Right(subscriptionList) =>
            logger.info(s"Successfully retrieved payment details result for identity user: $userId")
            val productsResponseWrites = new ProductsResponseWrites(catalog)
            val response = productsResponseWrites.from(user, subscriptionList)
            import productsResponseWrites.writes
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
  )(implicit logPrefix: LogPrefix): SimpleEitherT[List[AccountDetails]] = {
    for {
      fromZuora <- touchpointComponents.accountDetailsFromZuora.fetch(userId, filter)
      fromStripe <- touchpointComponents.guardianPatronService.getGuardianPatronAccountDetails(userId)
    } yield fromZuora ++ fromStripe
  }

  def fetchCancelledSubscriptions(): Action[AnyContent] =
    AuthorizeForRecentLogin(Return401IfNotSignedInRecently, requiredScopes = List(completeReadSelf)).async { implicit request =>
      import request.logPrefix
      metrics.measureDuration("GET /user-attributes/me/cancelled-subscriptions") {
        implicit val tp: TouchpointComponents = request.touchpoint
        val emptyResponse = Ok("[]")
        request.redirectAdvice.userId match {
          case Some(identityId) =>
            for {
              catalog <- tp.futureCatalog
              result <-
                (for {
                  contact <- OptionT(EitherT(tp.contactRepository.get(identityId)))
                  subs <- OptionT(EitherT(tp.subscriptionService.recentlyCancelled(contact)).map(Option(_)))
                } yield {
                  Ok(Json.toJson(subs.map(CancelledSubscription(_, catalog))))
                }).getOrElse(emptyResponse).leftMap(_ => emptyResponse).merge // we discard errors as this is not critical endpoint
            } yield result
          case None => Future.successful(unauthorized)
        }
      }
    }

  def updateContributionAmount(subscriptionName: String): Action[AnyContent] =
    AuthorizeForScopes(requiredScopes = List(readSelf, updateSelf)).async { implicit request =>
      import request.logPrefix
      metrics.measureDuration("POST /user-attributes/me/contribution-update-amount/:subscriptionName") {

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
              .current(contact)
              .map(subs => subscriptionSelector(memsub.Subscription.SubscriptionNumber(subscriptionName), s"the sfUser $contact", subs)),
          )
          catalog <- SimpleEitherT.rightT(services.futureCatalog)
          contributionPlan = subscription.plan(catalog)
          _ <- SimpleEitherT.fromEither(contributionPlan.product(catalog) match {
            case Contribution => Right(())
            case nc => Left(s"$subscriptionName plan is not a contribution: " + nc)
          })
          billingPeriod <- SimpleEitherT.fromEither(contributionPlan.billingPeriod.toEither)
          recurringPeriod <- SimpleEitherT.fromEither(billingPeriod match {
            case period: RecurringPeriod => Right(period)
            case period: BillingPeriod.OneOffPeriod => Left(s"period $period was not recurring for contribution update")
          })
          applyFromDate = contributionPlan.chargedThroughDate.getOrElse(contributionPlan.effectiveStartDate)
          currency = contributionPlan.chargesPrice.prices.head.currency
          currencyGlyph = currency.glyph
          oldPrice = contributionPlan.chargesPrice.prices.head.amount
          reasonForChange =
            s"User updated contribution via self-service MMA. Amount changed from $currencyGlyph$oldPrice to $currencyGlyph$newPrice effective from $applyFromDate"
          result <- SimpleEitherT(
            services.zuoraRestService.updateChargeAmount(
              subscription.subscriptionNumber,
              contributionPlan.ratePlanCharges.head.id,
              contributionPlan.id,
              newPrice.toDouble,
              reasonForChange,
              applyFromDate,
            ),
          ).leftMap(message => s"Error while updating contribution amount: $message")
          _ <- sendUpdateAmountEmail(newPrice, email, contact, currency, recurringPeriod, applyFromDate)
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
  )(implicit logPrefix: LogPrefix) =
    SimpleEitherT.right(sendEmail.send(updateAmountEmail(email, contact, newPrice, currency, billingPeriod, nextPaymentDate)))

  private def sendSubscriptionCancelledEmail(
      email: String,
      contact: Contact,
      productType: ProductType,
      cancellationEffectiveDate: Option[LocalDate],
  )(implicit logPrefix: LogPrefix) =
    SimpleEitherT
      .right(sendEmail.send(subscriptionCancelledEmail(email, contact, productType, cancellationEffectiveDate)))
      .leftMap(ApiError(_, "Email could not be put on the queue", 500))

  private[controllers] def validateContributionAmountUpdateForm(implicit request: Request[AnyContent]): Either[String, BigDecimal] = {
    val minAmount = 1
    for {
      amount <- Form(single("newPaymentAmount" -> bigDecimal(5, 2))).bindFromRequest().value.toRight("no new payment amount submitted with request")
      validAmount <- Either.cond(amount >= minAmount, amount, s"New payment amount '$amount' is too small")
    } yield validAmount
  }

  def updateCancellationReason(subscriptionName: String): Action[AnyContent] =
    AuthorizeForScopes(requiredScopes = List(readSelf, updateSelf)).async { implicit request =>
      import request.logPrefix
      metrics.measureDuration("POST /user-attributes/me/update-cancellation-reason/:subscriptionName") {
        val subName = memsub.Subscription.SubscriptionNumber(subscriptionName)
        val services = request.touchpoint
        val cancelForm = Form {
          single("reason" -> nonEmptyText)
        }
        val identityId = request.user.identityId
        val cancellationReasonOption = extractCancellationReason(cancelForm)

        cancellationReasonOption match {
          case Some(cancellationReason) =>
            services.zuoraRestService.updateCancellationReason(subName, cancellationReason).map {
              case -\/(error) =>
                logger.error(scrub"Failed to update cancellation reason for user $identityId because $error")
                InternalServerError(s"Failed to update cancellation reason with error: $error")
              case \/-(_) =>
                logger.info(s"Successfully updated cancellation reason for subscription $subscriptionName owned by $identityId")
                NoContent
            }
          case None =>
            Future.successful(BadRequest(Json.toJson(badRequest("Malformed request. Expected a valid reason for cancellation."))))
        }
      }
    }

  def allPaymentDetails(productType: Option[String]): Action[AnyContent] =
    anyPaymentDetails(
      productType.fold[OptionalSubscriptionsFilter](NoFilter)(FilterByProductType.apply),
      "GET /user-attributes/me/mma",
    )
  def paymentDetailsSpecificSub(subscriptionName: String): Action[AnyContent] =
    anyPaymentDetails(
      FilterBySubName(memsub.Subscription.SubscriptionNumber(subscriptionName)),
      "GET /user-attributes/me/mma/:subscriptionName",
    )
}
