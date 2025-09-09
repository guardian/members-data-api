package controllers

import actions.{AuthenticatedUserAndBackendRequest, CommonActions}
import com.gu.identity.auth.AccessScope
import com.gu.monitoring.SafeLogger.LogPrefix
import com.gu.monitoring.SafeLogging
import filters.AddGuIdentityHeaders
import loghandling.DeprecatedRequestLogger
import models.AccessScope.{completeReadSelf, readSelf}
import models.ApiError._
import models.ApiErrors._
import models.Features._
import models._
import monitoring.CreateMetrics
import org.apache.pekko.actor.ActorSystem
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
    addGuIdentityHeaders: AddGuIdentityHeaders,
    createMetrics: CreateMetrics,
)(implicit system: ActorSystem)
    extends BaseController
    with SafeLogging {
  import commonActions._
  implicit val executionContext: ExecutionContext = controllerComponents.executionContext
  lazy val metrics = createMetrics.forService(classOf[AttributeController])
  lazy val batchedMetrics = createMetrics.batchedForService(classOf[AttributeController])

  private def getLatestOneOffContributionDate(identityId: String, user: UserFromToken)(implicit
      executionContext: ExecutionContext,
      logPrefix: LogPrefix,
  ): Future[Option[LocalDate]] = {
    // Only use one-off data if the user is email-verified
    if (user.userEmailValidated.contains(true)) {
      contributionsStoreDatabaseService.getLatestContribution(identityId) map {
        case Left(databaseError) =>
          // Failed to get one-off data, but this should not block the zuora request
          logger.error(scrub"DBERROR getting date: $databaseError")
          None
        case Right(maybeOneOff) =>
          maybeOneOff.map(oneOff => new LocalDate(oneOff.created.toInstant.toEpochMilli))
      }
    } else Future.successful(None)
  }

  private def getLatestMobileSubscription(
      identityId: String,
  )(implicit executionContext: ExecutionContext, logPrefix: LogPrefix): Future[Option[MobileSubscriptionStatus]] = {
    mobileSubscriptionService.getSubscriptionStatusForUser(identityId).transform {
      case Failure(error) =>
        metrics.incrementCount(s"mobile-subscription-fetch-exception")
        logger.warn("Exception while fetching mobile subscription, assuming none", error)
        Success(None)
      case Success(Left(error)) =>
        metrics.incrementCount(s"mobile-subscription-fetch-error-non-http-200")
        logger.warn(s"Unable to fetch mobile subscription, assuming none: $error")
        Success(None)
      case Success(Right(status)) => Success(status)
    }
  }

  private def addOneOffAndMobile(
      attributes: Attributes,
      latestOneOffDate: Option[LocalDate],
      mobileSubscriptionStatus: Option[MobileSubscriptionStatus],
  ): Attributes = {
    val liveAppMobileExpiryDate = mobileSubscriptionStatus.flatMap { status =>
      val isLiveAppSubscription = status.platform.exists(p => p == "android" || p == "ios")
      if (isLiveAppSubscription) {
        Some(status.to.toLocalDate)
      } else {
        None // Don't set Live App expiry for other subscriptions (Feast, Puzzles, etc)
      }
    }

    attributes.copy(
      OneOffContributionDate = latestOneOffDate,
      LiveAppSubscriptionExpiryDate = liveAppMobileExpiryDate,
    )
  }

  protected def getSupporterProductDataAttributes(identityId: String)(implicit request: AuthenticatedUserAndBackendRequest[AnyContent]) = {
    import request.logPrefix
    logger.info(s"Fetching attributes from supporter-product-data table for user $identityId")
    request.touchpoint.supporterProductDataService
      .getNonCancelledAttributes(identityId)
      .map(maybeAttributes => ("supporter-product-data", maybeAttributes.getOrElse(None)))
  }

  private def lookup(
      endpointDescription: String,
      onSuccessMember: Attributes => Result,
      onSuccessSupporter: Attributes => Result,
      onNotFound: Result,
      sendAttributesIfNotFound: Boolean = false,
      requiredScopes: List[AccessScope],
      metricName: String,
      useBatchedMetrics: Boolean = false,
  ): Action[AnyContent] = {
    AuthorizeForScopes(requiredScopes).async { implicit request =>
      import request.logPrefix
      val future = {
        if (endpointDescription == "membership" || endpointDescription == "features") {
          DeprecatedRequestLogger.logDeprecatedRequest(request)
        }

        val user = request.user
        // execute futures outside of the for comprehension so they are executed in parallel rather than in sequence
        val futureSupporterAttributes = getSupporterProductDataAttributes(user.identityId)(request)
        val futureOneOffContribution = getLatestOneOffContributionDate(user.identityId, user)
        val futureMobileSubscriptionStatus = getLatestMobileSubscription(user.identityId)

        (for {
          // Fetch one-off data independently of zuora data so that we can handle users with no zuora record
          (fromWhere: String, supporterAttributes: Option[Attributes]) <- futureSupporterAttributes
          latestOneOffDate: Option[LocalDate] <- futureOneOffContribution
          latestMobileSubscription: Option[MobileSubscriptionStatus] <- futureMobileSubscriptionStatus
          supporterOrStaffAttributes: Option[Attributes] = maybeAllowAccessToDigipackForGuardianEmployees(
            // transforming to Option here because type of failure is no longer relevant at this point
            request.user,
            supporterAttributes,
            user.identityId,
          )
          allProductAttributes: Option[Attributes] = supporterOrStaffAttributes.map(
            addOneOffAndMobile(_, latestOneOffDate, latestMobileSubscription),
          )
        } yield {

          val result = allProductAttributes match {
            case Some(attrs @ Attributes(_, Some(tier), _, _, _, _, _, _, _, _, _, _, _, _)) =>
              logger.info(
                s"${user.identityId} is a $tier member - $endpointDescription - $attrs found via $fromWhere",
              )
              onSuccessMember(attrs).withHeaders(
                "X-Gu-Membership-Tier" -> tier,
                "X-Gu-Membership-Is-Paid-Tier" -> attrs.isPaidTier.toString,
              )
            case Some(attrs) =>
              attrs.DigitalSubscriptionExpiryDate.foreach { date =>
                logger.info(s"${user.identityId} is a digital subscriber expiring $date")
              }
              attrs.PaperSubscriptionExpiryDate.foreach { date =>
                logger.info(s"${user.identityId} is a paper subscriber expiring $date")
              }
              attrs.GuardianWeeklySubscriptionExpiryDate.foreach { date =>
                logger.info(
                  s"${user.identityId} is a Guardian Weekly subscriber expiring $date",
                )
              }
              attrs.GuardianPatronExpiryDate.foreach { date =>
                logger.info(s"${user.identityId} is a Guardian Patron expiring $date")
              }
              attrs.RecurringContributionPaymentPlan.foreach { paymentPlan =>
                logger.info(s"${user.identityId} is a regular $paymentPlan contributor")
              }
              logger.info(s"${user.identityId} supports the guardian - $attrs - found via $fromWhere")
              onSuccessSupporter(attrs)
            case None if sendAttributesIfNotFound =>
              val attr = addOneOffAndMobile(Attributes(user.identityId), latestOneOffDate, latestMobileSubscription)
              logger.info(s"${user.identityId} does not have zuora attributes - $attr - found via $fromWhere")
              Ok(Json.toJson(attr))
            case _ =>
              onNotFound
          }
          addGuIdentityHeaders.fromUser(result, user)

        }).recover { case e =>
          // This branch indicates a serious error to be investigated ASAP, because it likely means we could not
          // serve from either Zuora or DynamoDB cache. Likely multi-system outage in progress or logic error.
          val errMsg = scrub"Failed to serve entitlements either from cache or directly. Urgently notify Retention team: $e"
          metrics.incrementCount(s"$endpointDescription-failed-to-serve-entitlements")
          logger.error(errMsg, e)
          InternalServerError("failed to serve entitlements")
        }
      }
      if (useBatchedMetrics) {
        batchedMetrics.incrementCount(metricName)
        future
      } else {
        metrics.measureDuration(metricName)(future)
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

  def membership =
    lookup(
      endpointDescription = "membership",
      onSuccessMember = membershipAttributesFromAttributes,
      onSuccessSupporter = _ => ApiError("Not found", "User was found but they are not a member", 404),
      onNotFound = notFound,
      requiredScopes = List(completeReadSelf),
      metricName = "GET /user-attributes/me/membership",
    )

  def attributes =
    lookup(
      endpointDescription = "attributes",
      onSuccessMember = attrs => Ok(Json.toJson(attrs)),
      onSuccessSupporter = attrs => Ok(Json.toJson(attrs)),
      onNotFound = notFound,
      sendAttributesIfNotFound = true,
      requiredScopes = List(readSelf),
      metricName = "GET /user-attributes/me",
      useBatchedMetrics = true,
    )

  def features =
    lookup(
      endpointDescription = "features",
      onSuccessMember = Features.fromAttributes,
      onSuccessSupporter = _ => Features.unauthenticated,
      onNotFound = Features.unauthenticated,
      requiredScopes = List(completeReadSelf),
      metricName = "GET /user-attributes/me/features",
    )

  def oneOffContributions =
    AuthorizeForScopes(requiredScopes = List(readSelf)).async { implicit request =>
      metrics.measureDuration("GET /user-attributes/me/one-off-contributions") {
        import request.logPrefix
        val userHasValidatedEmail = request.user.userEmailValidated.getOrElse(false)

        val futureResult: Future[Result] =
          if (userHasValidatedEmail) {
            contributionsStoreDatabaseService.getAllContributions(request.user.identityId).map {
              case Left(err) =>
                logger.error(scrub"Error accessing contributions store database: $err")
                InternalServerError("Could not access contributions, check the logs for details")
              case Right(result) =>
                logger.info(s"found contributions:\n  ${result.mkString("\n  ")}")
                Ok(Json.toJson(result).toString)
            }
          } else Future(unauthorized)

        futureResult.map(addGuIdentityHeaders.fromUser(_, request.user))
      }
    }

  /** Allow all validated guardian.co.uk/theguardian.com email addresses access to the digipack
    */
  private def maybeAllowAccessToDigipackForGuardianEmployees(
      user: UserFromToken,
      maybeAttributes: Option[Attributes],
      identityId: String,
  ): Option[Attributes] = {
    val email = user.primaryEmailAddress
    val allowDigiPackAccessToStaff =
      (for {
        userHasValidatedEmail <- user.userEmailValidated
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

  def isTestUser: Action[AnyContent] = {
    val scope = List(readSelf) // this doesn't end in .secure so we won't call through to okta
    AuthorizeForScopes(scope) {
      NoContent
    }
  }

}
