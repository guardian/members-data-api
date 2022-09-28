package controllers

import actions._
import akka.actor.ActorSystem
import filters.AddGuIdentityHeaders
import loghandling.LoggingField.{LogField, LogFieldString}
import loghandling.{DeprecatedRequestLogger, LoggingWithLogstashFields}
import models.ApiError._
import models.ApiErrors._
import models.Features._
import models._
import monitoring.{ExpensiveMetrics, Metrics}
import org.joda.time.LocalDate
import play.api.libs.json.Json
import play.api.mvc._
import services._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** What benefits is the user entitled to?
  */
class AttributeController(
    commonActions: CommonActions,
    override val controllerComponents: ControllerComponents,
    contributionsStoreDatabaseService: ContributionsStoreDatabaseService,
    mobileSubscriptionService: MobileSubscriptionService,
)(implicit system: ActorSystem)
    extends BaseController
    with LoggingWithLogstashFields {
  import commonActions._
  implicit val executionContext: ExecutionContext = controllerComponents.executionContext
  lazy val metrics = Metrics("AttributesController")
  lazy val expensiveMetrics = new ExpensiveMetrics("AttributesController")

  private def getLatestOneOffContributionDate(identityId: String, user: AccessClaims)(implicit
      executionContext: ExecutionContext,
  ): Future[Option[LocalDate]] = {
    // Only use one-off data if the user is email-verified
    if (user.hasValidatedEmail) {
      contributionsStoreDatabaseService.getLatestContribution(identityId) map {
        case Left(databaseError) =>
          // Failed to get one-off data, but this should not block the zuora request
          log.error(databaseError)
          None
        case Right(maybeOneOff) =>
          maybeOneOff.map(oneOff => new LocalDate(oneOff.created.toInstant.toEpochMilli))
      }
    } else Future.successful(None)
  }

  private def getLatestMobileSubscription(
      identityId: String,
  )(implicit executionContext: ExecutionContext): Future[Option[MobileSubscriptionStatus]] = {
    mobileSubscriptionService.getSubscriptionStatusForUser(identityId).transform {
      case Failure(error) =>
        metrics.put(s"mobile-subscription-fetch-exception", 1)
        log.warn("Exception while fetching mobile subscription, assuming none", error)
        Success(None)
      case Success(Left(error)) =>
        metrics.put(s"mobile-subscription-fetch-error-non-http-200", 1)
        log.warn(s"Unable to fetch mobile subscription, assuming none: $error")
        Success(None)
      case Success(Right(status)) => Success(status)
    }
  }

  private def addOneOffAndMobile(
      attributes: Attributes,
      latestOneOffDate: Option[LocalDate],
      mobileSubscriptionStatus: Option[MobileSubscriptionStatus],
  ): Attributes = {
    val mobileExpiryDate = mobileSubscriptionStatus.map(_.to.toLocalDate)
    attributes.copy(
      OneOffContributionDate = latestOneOffDate,
      LiveAppSubscriptionExpiryDate = mobileExpiryDate,
    )
  }

  protected def getSupporterProductDataAttributes(identityId: String)(implicit request: AuthenticatedUserAndBackendRequest[AnyContent]) = {
    log.info(s"Fetching attributes from supporter-product-data table for user $identityId")
    request.touchpoint.supporterProductDataService
      .getAttributes(identityId)
      .map(maybeAttributes => ("supporter-product-data", maybeAttributes.getOrElse(None)))
  }

  private def isLiveApp(ua: String): Boolean = ua.matches("""^Guardian(News)?\/.*""")
  private def upgradeRecurringContributorsOnApps(userAgent: Option[String], attributes: Attributes): Attributes =
    if (attributes.HighContributor.contains(true) && userAgent.exists(isLiveApp)) {
      attributes.copy(SupporterPlusExpiryDate = Some(LocalDate.now().plusDays(1)))
    } else attributes

  private def lookup(
      endpointDescription: String,
      onSuccessMember: Attributes => Result,
      onSuccessSupporter: Attributes => Result,
      onNotFound: Result,
      sendAttributesIfNotFound: Boolean = false,
  ) = {
    AuthAndBackendViaAuthLibAction.async { implicit request =>
      if (endpointDescription == "membership" || endpointDescription == "features") {
        DeprecatedRequestLogger.logDeprecatedRequest(request)
      }

      request.user match {
        case Some(user) =>
          // execute futures outside of the for comprehension so they are executed in parallel rather than in sequence
          val futureSupporterAttributes = getSupporterProductDataAttributes(user.id)
          val futureOneOffContribution = getLatestOneOffContributionDate(user.id, user)
          val futureMobileSubscriptionStatus = getLatestMobileSubscription(user.id)

          (for {
            // Fetch one-off data independently of zuora data so that we can handle users with no zuora record
            (fromWhere: String, supporterAttributes: Option[Attributes]) <- futureSupporterAttributes
            latestOneOffDate: Option[LocalDate] <- futureOneOffContribution
            latestMobileSubscription: Option[MobileSubscriptionStatus] <- futureMobileSubscriptionStatus
            supporterOrStaffAttributes: Option[Attributes] = maybeAllowAccessToDigipackForGuardianEmployees(
              request.user,
              supporterAttributes,
              user.id,
            )
            allProductAttributes: Option[Attributes] = supporterOrStaffAttributes
              .map(addOneOffAndMobile(_, latestOneOffDate, latestMobileSubscription))
              .map(upgradeRecurringContributorsOnApps(request.headers.get(USER_AGENT), _))
          } yield {

            def customFields(supporterType: String): List[LogField] = List(
              LogFieldString("lookup-endpoint-description", endpointDescription),
              LogFieldString("supporter-type", supporterType),
              LogFieldString("data-source", fromWhere),
            )

            val result = allProductAttributes match {
              case Some(attrs @ Attributes(_, Some(tier), _, _, _, _, _, _, _, _, _, _, _)) =>
                logInfoWithCustomFields(s"${user.id} is a $tier member - $endpointDescription - $attrs found via $fromWhere", customFields("member"))
                onSuccessMember(attrs).withHeaders(
                  "X-Gu-Membership-Tier" -> tier,
                  "X-Gu-Membership-Is-Paid-Tier" -> attrs.isPaidTier.toString,
                )
              case Some(attrs) =>
                attrs.DigitalSubscriptionExpiryDate.foreach { date =>
                  logInfoWithCustomFields(s"${user.id} is a digital subscriber expiring $date", customFields("digital-subscriber"))
                }
                attrs.PaperSubscriptionExpiryDate.foreach { date =>
                  logInfoWithCustomFields(s"${user.id} is a paper subscriber expiring $date", customFields("paper-subscriber"))
                }
                attrs.GuardianWeeklySubscriptionExpiryDate.foreach { date =>
                  logInfoWithCustomFields(s"${user.id} is a Guardian Weekly subscriber expiring $date", customFields("guardian-weekly-subscriber"))
                }
                attrs.GuardianPatronExpiryDate.foreach { date =>
                  logInfoWithCustomFields(s"${user.id} is a Guardian Patron expiring $date", customFields("guardian-patron"))
                }
                attrs.RecurringContributionPaymentPlan.foreach { paymentPlan =>
                  logInfoWithCustomFields(s"${user.id} is a regular $paymentPlan contributor", customFields("contributor"))
                }
                logInfoWithCustomFields(s"${user.id} supports the guardian - $attrs - found via $fromWhere", customFields("supporter"))
                onSuccessSupporter(attrs)
              case None if sendAttributesIfNotFound =>
                val attr = addOneOffAndMobile(Attributes(user.id), latestOneOffDate, latestMobileSubscription)
                log.logger.info(s"${user.id} does not have zuora attributes - $attr - found via $fromWhere")
                Ok(Json.toJson(attr))
              case _ =>
                onNotFound
            }
            AddGuIdentityHeaders.fromUser(result, user)

          }).recover { case e =>
            // This branch indicates a serious error to be investigated ASAP, because it likely means we could not
            // serve from either Zuora or DynamoDB cache. Likely multi-system outage in progress or logic error.
            val errMsg = s"Failed to serve entitlements either from cache or directly. Urgently notify Retention team: $e"
            metrics.put(s"$endpointDescription-failed-to-serve-entitlements", 1)
            log.error(errMsg, e)
            InternalServerError(errMsg)
          }
        case None =>
          metrics.put(s"$endpointDescription-cookie-auth-failed", 1)
          Future(unauthorized)
      }
    }
  }

  private val notFound = ApiError("Not found", "Could not find user in the database", 404)

  private def membershipAttributesFromAttributes(attributes: Attributes): Result = {
    MembershipAttributes
      .fromAttributes(attributes)
      .map(member => Ok(Json.toJson(member)))
      .getOrElse(notFound)
  }

  def membership = lookup(
    endpointDescription = "membership",
    onSuccessMember = membershipAttributesFromAttributes,
    onSuccessSupporter = _ => ApiError("Not found", "User was found but they are not a member", 404),
    onNotFound = notFound,
  )
  def attributes = lookup(
    endpointDescription = "attributes",
    onSuccessMember = attrs => Ok(Json.toJson(attrs)),
    onSuccessSupporter = attrs => Ok(Json.toJson(attrs)),
    onNotFound = notFound,
    sendAttributesIfNotFound = true,
  )
  def features = lookup(
    endpointDescription = "features",
    onSuccessMember = Features.fromAttributes,
    onSuccessSupporter = _ => Features.unauthenticated,
    onNotFound = Features.unauthenticated,
  )

  def oneOffContributions = {
    AuthAndBackendViaAuthLibAction.async { implicit request =>
      val userHasValidatedEmail = request.user.exists(_.hasValidatedEmail)

      val futureResult: Future[Result] = if (userHasValidatedEmail) {
        request.user.map(_.id) match {
          case Some(identityId) =>
            contributionsStoreDatabaseService.getAllContributions(identityId).map {
              case Left(err) => Ok(err)
              case Right(result) => Ok(Json.toJson(result).toString)
            }
          case None => Future(unauthorized)
        }
      } else
        Future(unauthorized)

      futureResult.map { result =>
        request.user match {
          case Some(user) => AddGuIdentityHeaders.fromUser(result, user)
          case None => result
        }
      }
    }
  }

  /** Allow all validated guardian.co.uk/theguardian.com email addresses access to the digipack
    */
  private def maybeAllowAccessToDigipackForGuardianEmployees(
      maybeUser: Option[AccessClaims],
      maybeAttributes: Option[Attributes],
      identityId: String,
  ): Option[Attributes] = {

    val allowDigiPackAccessToStaff =
      (for {
        user <- maybeUser
        email = user.primaryEmailAddress
        userHasValidatedEmail = user.hasValidatedEmail
        emailDomain <- email.split("@").lastOption
        userHasGuardianEmail = List("guardian.co.uk", "theguardian.com").contains(emailDomain)
      } yield {
        userHasValidatedEmail && userHasGuardianEmail
      }).getOrElse(false)

    // if maybeAttributes == None, there is nothing in Zuora so we have to hack it
    lazy val mockedZuoraAttribs = Some(Attributes(identityId))
    lazy val digipackAllowEmployeeAccessDateHack = Some(new LocalDate(2999, 1, 1))
    if (allowDigiPackAccessToStaff)
      (maybeAttributes orElse mockedZuoraAttribs).map(_.copy(DigitalSubscriptionExpiryDate = digipackAllowEmployeeAccessDateHack))
    else
      maybeAttributes

  }

}
