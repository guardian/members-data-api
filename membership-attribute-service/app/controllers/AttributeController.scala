package controllers

import actions._
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.monitoring.SafeLogger._
import com.gu.monitoring.SafeLogger
import configuration.Config
import loghandling.LoggingField.{LogField, LogFieldString}
import loghandling.{LoggingWithLogstashFields, ZuoraRequestCounter}
import models.ApiError._
import models.ApiErrors._
import models.Features._
import models._
import monitoring.Metrics
import play.api.libs.json.Json
import play.api.mvc._
import services.{AttributesFromZuora, AuthenticationService, IdentityAuthService}

import scala.concurrent.{ExecutionContext, Future}

class AttributeController(attributesFromZuora: AttributesFromZuora, commonActions: CommonActions, override val controllerComponents: ControllerComponents) extends BaseController with LoggingWithLogstashFields {
  import attributesFromZuora._
  import commonActions._
  implicit val executionContext: ExecutionContext = controllerComponents.executionContext
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val metrics = Metrics("AttributesController")

  def pickAttributes(identityId: String) (implicit request: BackendRequest[AnyContent]): Future[(String, Option[Attributes])] = {
    SafeLogger.error(scrub"*TEST* more spam from Leigh-Anne: Don't tell, reader, but you are my favourite colleague. ")
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
      val attributes: Future[Option[Attributes]] = dynamoService.get(identityId).map(maybeDynamoAttributes => maybeDynamoAttributes.map(DynamoAttributes.asAttributes(_)))(executionContext)
      attributes.map(("Dynamo - too many concurrent calls to Zuora", _))(executionContext)
    }
  }

  private def lookup(endpointDescription: String, onSuccessMember: Attributes => Result, onSuccessSupporter: Attributes => Result, onNotFound: Result, sendAttributesIfNotFound: Boolean = false) = {
    BackendFromCookieAction.async { implicit request =>
      authenticationService.userId(request) match {
        case Some(identityId) =>
          pickAttributes(identityId) map { pickedAttributes =>

            val (fromWhere: String, attributes: Option[Attributes]) = pickedAttributes

            def customFields(supporterType: String): List[LogField] = List(LogFieldString("lookup-endpoint-description", endpointDescription), LogFieldString("supporter-type", supporterType), LogFieldString("data-source", fromWhere))

            attributes match {
              case Some(attrs @ Attributes(_, Some(tier), _, _, _, _, _, _)) =>
                logInfoWithCustomFields(s"$identityId is a $tier member - $endpointDescription - $attrs found via $fromWhere", customFields("member"))
                onSuccessMember(attrs).withHeaders(
                  "X-Gu-Membership-Tier" -> tier,
                  "X-Gu-Membership-Is-Paid-Tier" -> attrs.isPaidTier.toString
                )
              case Some(attrs) =>
                attrs.DigitalSubscriptionExpiryDate.foreach { date =>
                  logInfoWithCustomFields(s"$identityId is a digital subscriber expiring $date", customFields("digital-subscriber"))
                }
                attrs.RecurringContributionPaymentPlan.foreach { paymentPlan =>
                  logInfoWithCustomFields(s"$identityId is a regular $paymentPlan contributor", customFields("contributor"))
                }
                attrs.AdFree.foreach { _ =>
                  logInfoWithCustomFields(s"$identityId is an ad-free reader", customFields("ad-free-reader"))
                }
                logInfoWithCustomFields(s"$identityId supports the guardian - $attrs - found via $fromWhere", customFields("supporter"))
                onSuccessSupporter(attrs)
              case None if sendAttributesIfNotFound =>
                Attributes(identityId, AdFree = Some(false))
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
  def features = lookup("features", onSuccessMember = Features.fromAttributes, onSuccessSupporter = Features.notAMember, onNotFound = Features.unauthenticated)

}
