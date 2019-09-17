package controllers

import actions._
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
import cats.implicits._
import com.gu.identity.model.User
import org.joda.time.LocalDate
import scalaz.{-\/, \/-}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class AttributeController(attributesFromZuora: AttributesFromZuora, commonActions: CommonActions, override val controllerComponents: ControllerComponents, oneOffContributionDatabaseService: OneOffContributionDatabaseService) extends BaseController with LoggingWithLogstashFields {
  import attributesFromZuora._
  import commonActions._
  implicit val executionContext: ExecutionContext = controllerComponents.executionContext
  lazy val metrics = Metrics("AttributesController")

  def pickAttributes(identityId: String) (implicit request: AuthenticatedUserAndBackendRequest[AnyContent]): Future[(String, Option[Attributes])] = {
    val dynamoService = request.touchpoint.attrService
    val featureToggleData = request.touchpoint.featureToggleData.getZuoraLookupFeatureDataTask.get()
    val concurrentCallThreshold = featureToggleData.ConcurrentZuoraCallThreshold

    if (ZuoraRequestCounter.get < concurrentCallThreshold) {
      getAttributes(
        identityId = identityId,
        identityIdToAccountIds = request.touchpoint.zuoraRestService.getAccounts,
        subscriptionsForAccountId = accountId => reads => request.touchpoint.subService.subscriptionsForAccountId[AnyPlan](accountId)(reads),
        dynamoAttributeService = dynamoService,
        paymentMethodForPaymentMethodId = paymentMethodId => request.touchpoint.zuoraRestService.getPaymentMethod(paymentMethodId.get)
      )
    } else {
      val attributes: Future[Option[Attributes]] = dynamoService.get(identityId).map(maybeDynamoAttributes => maybeDynamoAttributes.map(DynamoAttributes.asAttributes(_, None)))(executionContext)
      attributes.map(("Dynamo - too many concurrent calls to Zuora", _))(executionContext)
    }
  }

  def getLatestOneOffContributionDate(identityId: String, userHasValidated: Boolean)(implicit executionContext: ExecutionContext): Future[Option[LocalDate]] = {
    // Only use one-off data if the user is email-verified

    if (userHasValidated) {
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

  private def lookup(endpointDescription: String, onSuccessMember: Attributes => Result, onSuccessSupporter: Attributes => Result, onNotFound: Result, sendAttributesIfNotFound: Boolean = false) = {
    AuthAndBackendViaAuthLibAction.async { implicit request =>

      if(endpointDescription == "membership" || endpointDescription == "features") {
        DeprecatedRequestLogger.logDeprecatedRequest(request)
      }

      val userHasValidatedEmail = request.user.flatMap(_.statusFields.userEmailValidated).getOrElse(false)

      request.user.map(_.id) match {
        case Some(identityId) =>
          for {
            //Fetch one-off data independently of zuora data so that we can handle users with no zuora record
            (fromWhere: String, zuoraAttributes: Option[Attributes]) <- pickAttributes(identityId)
            latestOneOffDate: Option[LocalDate] <- getLatestOneOffContributionDate(identityId, userHasValidatedEmail)
            zuoraAttribWithContrib: Option[Attributes] = zuoraAttributes.map(_.copy(OneOffContributionDate = latestOneOffDate))
            combinedAttributes: Option[Attributes] = maybeAllowAccessToDigipackForGuardianEmployees(request.user, zuoraAttribWithContrib, identityId)
          } yield {

            def customFields(supporterType: String): List[LogField] = List(LogFieldString("lookup-endpoint-description", endpointDescription), LogFieldString("supporter-type", supporterType), LogFieldString("data-source", fromWhere))

            combinedAttributes match {
              case Some(attrs @ Attributes(_, Some(tier), _, _, _, _, _, _, _)) =>
                logInfoWithCustomFields(s"$identityId is a $tier member - $endpointDescription - $attrs found via $fromWhere", customFields("member"))
                onSuccessMember(attrs).withHeaders(
                  "X-Gu-Membership-Tier" -> tier,
                  "X-Gu-Membership-Is-Paid-Tier" -> attrs.isPaidTier.toString
                )
              case Some(attrs) =>
                attrs.DigitalSubscriptionExpiryDate.foreach { date =>
                  logInfoWithCustomFields(s"$identityId is a digital subscriber expiring $date", customFields("digital-subscriber"))
                }
                attrs.PaperSubscriptionExpiryDate.foreach {date =>
                  logInfoWithCustomFields(s"$identityId is a paper subscriber expiring $date", customFields("paper-subscriber"))
                }
                attrs.GuardianWeeklySubscriptionExpiryDate.foreach {date =>
                  logInfoWithCustomFields(s"$identityId is a Guardian Weekly subscriber expiring $date", customFields("guardian-weekly-subscriber"))
                }
                attrs.RecurringContributionPaymentPlan.foreach { paymentPlan =>
                  logInfoWithCustomFields(s"$identityId is a regular $paymentPlan contributor", customFields("contributor"))
                }
                logInfoWithCustomFields(s"$identityId supports the guardian - $attrs - found via $fromWhere", customFields("supporter"))
                onSuccessSupporter(attrs)
              case None if sendAttributesIfNotFound =>
                Attributes(identityId, OneOffContributionDate = latestOneOffDate)
              case _ =>
                onNotFound
            }
          }
        case None =>
          metrics.put(s"$endpointDescription-cookie-auth-failed", 1)
          Future(unauthorized)
        }
      }
  }

  val notFound = ApiError("Not found", "Could not find user in the database", 404)
  val notAMember = ApiError("Not found", "User was found but they are not a member", 404)

  private def membershipAttributesFromAttributes(attributes: Attributes): Result = {
     MembershipAttributes.fromAttributes(attributes)
       .map(member => Ok(Json.toJson(member)))
       .getOrElse(notFound)
  }

  def membership = lookup("membership", onSuccessMember = membershipAttributesFromAttributes, onSuccessSupporter = _ => notAMember, onNotFound = notFound)
  def attributes = lookup("attributes", onSuccessMember = identity[Attributes], onSuccessSupporter = identity[Attributes], onNotFound = notFound, sendAttributesIfNotFound = true)
  def features = lookup("features", onSuccessMember = Features.fromAttributes, onSuccessSupporter = _ => Features.unauthenticated, onNotFound = Features.unauthenticated)


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
