package controllers

import actions._
import akka.stream.Materializer
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import components.TouchpointBackends
import configuration.Config
import loghandling.LoggingField.{LogField, LogFieldString}
import loghandling.{LoggingWithLogstashFields, ZuoraRequestCounter}
import models.ApiError._
import models.ApiErrors._
import models.Features._
import models._
import monitoring.Metrics
import play.api.http.{DefaultHttpErrorHandler, ParserConfiguration}
import play.api.libs.Files.SingletonTemporaryFileCreator
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc._
import play.filters.cors.CORSActionBuilder
import services.{AuthenticationService, IdentityAuthService}

import scala.concurrent.Future
import services.AttributesFromZuora._

class AttributeController(commonActions: CommonActions)(implicit val mat:Materializer) extends Controller with LoggingWithLogstashFields {

  import commonActions._
  lazy val corsFilter = CORSActionBuilder(Config.corsConfig, DefaultHttpErrorHandler, ParserConfiguration() , SingletonTemporaryFileCreator)
  lazy val backendAction = NoCacheAction andThen corsFilter andThen BackendFromCookieAction
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val metrics = Metrics("AttributesController")

  def pickAttributes(identityId: String) (implicit request: BackendRequest[AnyContent]): Future[(String, Option[Attributes])] = {
    val dynamoService = request.touchpoint.attrService
    val featureToggleData = request.touchpoint.featureToggleData.getZuoraLookupFeatureDataTask.get()
    val concurrentCallThreshold = featureToggleData.ConcurrentZuoraCallThreshold
    if (ZuoraRequestCounter.get < concurrentCallThreshold) {
      getAttributes(
        identityId = identityId,
        identityIdToAccountIds = request.touchpoint.zuoraRestService.getAccounts,
        subscriptionsForAccountId = accountId => reads => request.touchpoint.subService.subscriptionsForAccountId[AnyPlan](accountId)(reads),
        dynamoAttributeService = dynamoService
      )
    } else {
      val attributes: Future[Option[Attributes]] = dynamoService.get(identityId) map { maybeDynamoAttributes => maybeDynamoAttributes.map(DynamoAttributes.asAttributes(_))}
      attributes map {("Dynamo - too many concurrent calls to Zuora", _)}
    }
  }

  private def lookup(endpointDescription: String, onSuccessMember: Attributes => Result, onSuccessSupporter: Attributes => Result, onNotFound: Result, sendAttributesIfNotFound: Boolean = false) = {

    backendAction.async { implicit request =>
      authenticationService.userId(request) match {
        case Some(identityId) =>
          pickAttributes(identityId) map { pickedAttributes =>

            val (fromWhere: String, attributes: Option[Attributes]) = pickedAttributes

            def customFields(supporterType: String): List[LogField] = List(LogFieldString("lookup-endpoint-description", endpointDescription), LogFieldString("supporter-type", supporterType), LogFieldString("data-source", fromWhere))

            attributes match {
              case Some(attrs @ Attributes(_, Some(tier), _, _, _, _, _)) =>
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
