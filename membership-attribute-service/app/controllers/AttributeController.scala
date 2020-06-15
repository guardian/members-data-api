package controllers

import actions._
import akka.actor.ActorSystem
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import loghandling.LoggingField.{LogField, LogFieldString}
import loghandling.{DeprecatedRequestLogger, LoggingWithLogstashFields, ZuoraRequestCounter}
import models.ApiError._
import models.ApiErrors._
import models.Features._
import models._
import monitoring.Metrics
import play.api.libs.json.Json
import play.api.mvc._
import services._
import com.gu.identity.model.User
import org.joda.time.LocalDate
import scalaz.{-\/, \/-}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/**
 *  What benefits is the user entitled to?
 */
class AttributeController(
  attributesFromZuora: AttributesFromZuora,
  commonActions: CommonActions,
  override val controllerComponents: ControllerComponents,
  oneOffContributionDatabaseService: OneOffContributionDatabaseService,
  mobileSubscriptionService: MobileSubscriptionService
)(implicit system: ActorSystem) extends BaseController with LoggingWithLogstashFields {
  import attributesFromZuora._
  import commonActions._
  implicit val executionContext: ExecutionContext = controllerComponents.executionContext
  lazy val metrics = Metrics("AttributesController")

  /**
   * Zuora enforces 40 concurrent requests limit, without providing caching mechanisms.
   * So the following custom workaround logic is attempted:
   *
   * 1. Count Zuora concurrent requests
   * 1. Get the concurrency limit set in `AttributesFromZuoraLookup` dynamodb table
   * 1. If the count is greater than limit, then hit cache
   * 1. If the count is less than limit and Zuora is healthy, then hit Zuora
   * 1. If the count is less than limit and Zuora is unhealthy, then hit cache
   */
  def getAttributesWithConcurrencyLimitHandling(identityId: String) (implicit request: AuthenticatedUserAndBackendRequest[AnyContent]): Future[(String, Option[Attributes])] = {
    val dynamoService = request.touchpoint.attrService
    val featureToggleData = request.touchpoint.featureToggleData.getZuoraLookupFeatureDataTask.get()
    val concurrentCallThreshold = featureToggleData.ConcurrentZuoraCallThreshold

    if (ZuoraRequestCounter.get < concurrentCallThreshold) {
      metrics.put(s"zuora-hit", 1)
      getAttributesFromZuoraWithCacheFallback(
        identityId = identityId,
        identityIdToAccounts = request.touchpoint.zuoraRestService.getAccounts,
        subscriptionsForAccountId = accountId => reads => request.touchpoint.subService.subscriptionsForAccountId[AnyPlan](accountId)(reads),
        dynamoAttributeService = dynamoService,
        paymentMethodForPaymentMethodId = paymentMethodId => request.touchpoint.zuoraRestService.getPaymentMethod(paymentMethodId.get)
      )
    } else {
      metrics.put(s"cache-hit", 1)
      dynamoService
        .get(identityId)
        .map(maybeDynamoAttributes => maybeDynamoAttributes.map(DynamoAttributes.asAttributes(_, None)))(executionContext)
        .map(("Dynamo - too many concurrent calls to Zuora", _))(executionContext)
    }
  }

  private def getLatestOneOffContributionDate(identityId: String, user: User)(implicit executionContext: ExecutionContext): Future[Option[LocalDate]] = {
    // Only use one-off data if the user is email-verified
    val userHasValidatedEmail = user.statusFields.userEmailValidated.getOrElse(false)

    if (userHasValidatedEmail) {
      oneOffContributionDatabaseService.getLatestContribution(identityId) map {
        case -\/(databaseError) =>
          //Failed to get one-off data, but this should not block the zuora request
          log.error(databaseError)
          None
        case \/-(maybeOneOff) =>
          maybeOneOff.map(oneOff => new LocalDate(oneOff.created.toInstant.toEpochMilli))
      }
    }
    else Future.successful(None)
  }

  private def getLatestMobileSubscription(identityId: String)(implicit executionContext: ExecutionContext): Future[Option[MobileSubscriptionStatus]] = {
    mobileSubscriptionService.getSubscriptionStatusForUser(identityId).transform {
      case Failure(NonFatal(error)) =>
        metrics.put(s"mobile-subscription-fetch-exception", 1)
        log.warn("Exception while fetching mobile subscription, assuming none", error)
        Success(None)
      case Success(-\/(error)) =>
        metrics.put(s"mobile-subscription-fetch-error-non-http-200", 1)
        log.warn(s"Unable to fetch mobile subscription, assuming none: $error")
        Success(None)
      case Success(\/-(status)) => Success(status)
    }
  }

  private def enrichZuoraAttributes(zuoraAttributes: Attributes, latestOneOffDate: Option[LocalDate], mobileSubscriptionStatus: Option[MobileSubscriptionStatus]): Attributes = {
    val mobileExpiryDate = mobileSubscriptionStatus.map(_.to.toLocalDate)
    zuoraAttributes.copy(
      OneOffContributionDate = latestOneOffDate,
      LiveAppSubscriptionExpiryDate = mobileExpiryDate,

    )
  }

  private def lookup(endpointDescription: String, onSuccessMember: Attributes => Result, onSuccessSupporter: Attributes => Result, onNotFound: Result, sendAttributesIfNotFound: Boolean = false) = {
    AuthAndBackendViaAuthLibAction.async { implicit request =>

      if(endpointDescription == "membership" || endpointDescription == "features") {
        DeprecatedRequestLogger.logDeprecatedRequest(request)
      }

      request.user match {
        case Some(user) =>
          // execute futures outside of the for comprehension so they are executed in parallel rather than in sequence
          val futureAttributes = getAttributesWithConcurrencyLimitHandling(user.id)
          val futureOneOffContribution = getLatestOneOffContributionDate(user.id, user)
          val futureMobileSubscriptionStatus = getLatestMobileSubscription(user.id)

          (for {
            //Fetch one-off data independently of zuora data so that we can handle users with no zuora record
            (fromWhere: String, zuoraAttributes: Option[Attributes]) <- futureAttributes
            latestOneOffDate: Option[LocalDate] <- futureOneOffContribution
            latestMobileSubscription: Option[MobileSubscriptionStatus] <- futureMobileSubscriptionStatus
            combinedAttributes: Option[Attributes] = maybeAllowAccessToDigipackForGuardianEmployees(request.user, zuoraAttributes, user.id)
            enrichedZuoraAttributes: Option[Attributes] = combinedAttributes.map(enrichZuoraAttributes(_, latestOneOffDate, latestMobileSubscription))
          } yield {

            def customFields(supporterType: String): List[LogField] = List(LogFieldString("lookup-endpoint-description", endpointDescription), LogFieldString("supporter-type", supporterType), LogFieldString("data-source", fromWhere))

            enrichedZuoraAttributes match {
              case Some(attrs @ Attributes(_, Some(tier), _, _, _, _, _, _, _, _)) =>
                logInfoWithCustomFields(s"${user.id} is a $tier member - $endpointDescription - $attrs found via $fromWhere", customFields("member"))
                onSuccessMember(attrs).withHeaders(
                  "X-Gu-Membership-Tier" -> tier,
                  "X-Gu-Membership-Is-Paid-Tier" -> attrs.isPaidTier.toString
                )
              case Some(attrs) =>
                attrs.DigitalSubscriptionExpiryDate.foreach { date =>
                  logInfoWithCustomFields(s"${user.id} is a digital subscriber expiring $date", customFields("digital-subscriber"))
                }
                attrs.PaperSubscriptionExpiryDate.foreach {date =>
                  logInfoWithCustomFields(s"${user.id} is a paper subscriber expiring $date", customFields("paper-subscriber"))
                }
                attrs.GuardianWeeklySubscriptionExpiryDate.foreach {date =>
                  logInfoWithCustomFields(s"${user.id} is a Guardian Weekly subscriber expiring $date", customFields("guardian-weekly-subscriber"))
                }
                attrs.RecurringContributionPaymentPlan.foreach { paymentPlan =>
                  logInfoWithCustomFields(s"${user.id} is a regular $paymentPlan contributor", customFields("contributor"))
                }
                logInfoWithCustomFields(s"${user.id} supports the guardian - $attrs - found via $fromWhere", customFields("supporter"))
                onSuccessSupporter(attrs)
              case None if sendAttributesIfNotFound =>
                val attr = enrichZuoraAttributes(Attributes(user.id), latestOneOffDate, latestMobileSubscription)
                Ok(Json.toJson(attr))
              case _ =>
                onNotFound
            }
          }).recover { case e =>
              // This branch indicates a serious error to be investigated ASAP, because it likely means we could not
              // serve from either Zuora or DynamoDB cache. Likely multi-system outage in progress or logic error.
              val errMsg = s"Failed to serve entitlements either from cache or directly. Urgently notify Supporter Experience team: $e"
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
     MembershipAttributes.fromAttributes(attributes)
       .map(member => Ok(Json.toJson(member)))
       .getOrElse(notFound)
  }

  def membership = lookup(
    endpointDescription = "membership",
    onSuccessMember = membershipAttributesFromAttributes,
    onSuccessSupporter = _ => ApiError("Not found", "User was found but they are not a member", 404),
    onNotFound = notFound
  )
  def attributes = lookup(
    endpointDescription = "attributes",
    onSuccessMember = attrs => Ok(Json.toJson(attrs)),
    onSuccessSupporter = attrs => Ok(Json.toJson(attrs)),
    onNotFound = notFound,
    sendAttributesIfNotFound = true
  )
  def features = lookup(
    endpointDescription = "features",
    onSuccessMember = Features.fromAttributes,
    onSuccessSupporter = _ => Features.unauthenticated,
    onNotFound = Features.unauthenticated
  )

  def oneOffContributions = {
    AuthAndBackendViaAuthLibAction.async { implicit request =>

      val userHasValidatedEmail = request.user.flatMap(_.statusFields.userEmailValidated).getOrElse(false)

      if (userHasValidatedEmail) {
        request.user.map(_.id) match {
          case Some(identityId) =>
            oneOffContributionDatabaseService.getAllContributions(identityId).map {
              case -\/(err) => Ok(err)
              case \/-(result) => Ok(Json.toJson(result).toString)
            }
          case None => Future(unauthorized)
        }
      } else Future(unauthorized)
    }
  }

  /**
   * Allow all validated guardian.co.uk/theguardian.com email addresses access to the digipack
   */
  private def maybeAllowAccessToDigipackForGuardianEmployees(
    maybeUser: Option[User],
    maybeAttributes: Option[Attributes],
    identityId: String,
  ): Option[Attributes] = {

    val allowDigiPackAccessToStaff =
      (for {
        user <- maybeUser
        email = user.primaryEmailAddress
        userHasValidatedEmail <- user.statusFields.userEmailValidated
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
