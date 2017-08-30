package controllers
import actions._
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.subsv2.services.SubscriptionService
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.scanamo.error.DynamoReadError
import com.gu.zuora.ZuoraRestService
import com.gu.zuora.ZuoraRestService.QueryResponse
import loghandling.ZuoraRequestCounter
import configuration.Config
import configuration.Config.authentication
import loghandling.LoggingField.LogField
import loghandling.LoggingWithLogstashFields
import models.ApiError._
import models.ApiErrors._
import models.Features._
import models._
import monitoring.Metrics
import org.joda.time.LocalDate
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc._
import play.filters.cors.CORSActionBuilder
import services.{AttributeService, AttributesMaker, AuthenticationService, IdentityAuthService}

import scala.concurrent.Future
import scalaz.std.scalaFuture._
import scalaz.syntax.std.option._
import prodtest.Allocator._

import scalaz.{-\/, Disjunction, EitherT, \/, \/-}
import scalaz.syntax.std.either._
import scalaz._
import std.list._
import syntax.traverse._


class AttributeController extends Controller with LoggingWithLogstashFields {

  val keys = authentication.keys.map(key => s"Bearer $key")

  def apiKeyFilter(): ActionBuilder[Request] = new ActionBuilder[Request] {
    def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {
      request.headers.get("Authorization") match {
        case Some(header) if keys.contains(header) => block(request)
        case _ => Future.successful(ApiErrors.invalidApiKey)
      }
    }
  }

  lazy val corsFilter = CORSActionBuilder(Config.corsConfig)
  lazy val backendAction = NoCacheAction andThen corsFilter andThen BackendFromCookieAction
  lazy val backendForSyncWithZuora = NoCacheAction andThen apiKeyFilter andThen WithBackendFromUserIdAction
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val metrics = Metrics("AttributesController")

  private def lookup(endpointDescription: String, onSuccessMember: Attributes => Result, onSuccessMemberAndOrContributor: Attributes => Result, onNotFound: Result, endpointEligibleForTest: Boolean) =
  {
    def pickAttributes(identityId: String) (implicit request: BackendRequest[AnyContent]): (String, Future[Option[Attributes]]) = {
      if(endpointEligibleForTest){
        val percentageInTest = request.touchpoint.featureToggleData.getPercentageTrafficForZuoraLookupTask.get()
        isInTest(identityId, percentageInTest) match {
          case true => ("Zuora", attributesFromZuora(identityId, request.touchpoint.patientZuoraRestService, request.touchpoint.subService, request.touchpoint.attrService))
          case false => ("Dynamo", request.touchpoint.attrService.get(identityId))
        }
      } else ("Dynamo", request.touchpoint.attrService.get(identityId))
    }

    backendAction.async { implicit request =>
      authenticationService.userId(request) match {
        case Some(identityId) =>
          val (fromWhere, attributes) = pickAttributes(identityId)
          attributes.map {
            case Some(attrs @ Attributes(_, Some(tier), _, _, _, _, _)) =>
              log.info(s"$identityId is a member - $endpointDescription - $attrs found via $fromWhere")
              onSuccessMember(attrs).withHeaders(
                "X-Gu-Membership-Tier" -> tier,
                "X-Gu-Membership-Is-Paid-Tier" -> attrs.isPaidTier.toString
              )
            case Some(attrs) =>
              log.info(s"$identityId is a contributor - $endpointDescription - $attrs found via $fromWhere")
              onSuccessMemberAndOrContributor(attrs)
            case _ =>
              onNotFound
          }
        case None =>
          metrics.put(s"$endpointDescription-cookie-auth-failed", 1)
          Future(unauthorized)

        }
      }
  }


  private def attributesFromZuora(identityId: String, patientZuoraRestService: ZuoraRestService[Future], subscriptionService: SubscriptionService[Future], attributeService: AttributeService): Future[Option[Attributes]] = {

    def withTimer[R](whichCall: String, futureResult: Future[Disjunction[String, R]]) = {
      import loghandling.StopWatch
      val stopWatch = new StopWatch

      futureResult.map { disjunction: Disjunction[String, R] =>
        disjunction match {
          case -\/(message) => log.warn(s"$whichCall failed with: $message")
          case \/-(_) =>
            val latency = stopWatch.elapsed
            val zuoraConcurrencyCount = ZuoraRequestCounter.get
            val customFields: List[LogField] = List("zuora_latency_millis" -> latency.toInt, "zuora_call" -> whichCall, "identityId" -> identityId, "zuora_concurrency_count" -> zuoraConcurrencyCount)
            logInfoWithCustomFields(s"$whichCall took ${latency}ms.", customFields)
        }
      }.onFailure {
        case e: Throwable => log.error(s"Future failed when attempting $whichCall.", e)
      }
      futureResult
    }

    def queryToAccountIds(response: QueryResponse): List[AccountId] =  response.records.map(_.Id)

    def getSubscriptions(accountIds: List[AccountId]): Future[Disjunction[String, List[Subscription[AnyPlan]]]] = {

      def sub(accountId: AccountId): Future[Disjunction[String, List[Subscription[AnyPlan]]]] =
        subscriptionService.subscriptionsForAccountId[AnyPlan](accountId)(anyPlanReads)

      val maybeSubs: Future[Disjunction[String, List[Subscription[AnyPlan]]]] = accountIds.traverseU(id => sub(id)).map(_.sequenceU.map(_.flatten))
      maybeSubs.map {
        _.leftMap { errorMsg =>
          log.warn(s"We tried getting subscription for a user with identityId $identityId, but then $errorMsg")
          s"We called Zuora to get subscriptions for a user with identityId $identityId but the call failed"
        } map { subs =>
          log.info(s"We got subs for identityId $identityId from Zuora and there were ${subs.length}")
          subs
        }
      }
    }

    def zuoraAccountsQuery(identityId: String): Future[Disjunction[String, QueryResponse]] = patientZuoraRestService.getAccounts(identityId).map {
      _.leftMap {error =>
        log.warn(s"Calling ZuoraAccountIdsFromIdentityId failed for identityId $identityId. with error: ${error}")
        s"Calling ZuoraAccountIdsFromIdentityId failed for identityId $identityId."
      }
    }

    val attributesDisjunction = for {
      accounts <- EitherT[Future, String, QueryResponse](withTimer(s"ZuoraAccountIdsFromIdentityId", zuoraAccountsQuery(identityId)))
      accountIds = queryToAccountIds(accounts)
      subscriptions <- EitherT[Future, String, List[Subscription[AnyPlan]]](
        if(accountIds.nonEmpty) withTimer(s"ZuoraGetSubscriptions", getSubscriptions(accountIds))
        else Future.successful(\/.right {
          log.info(s"User with identityId $identityId has no accountIds and thus no subscriptions.")
          Nil
        })
      )
    } yield {
      AttributesMaker.attributes(identityId, subscriptions, LocalDate.now())
    }

    val attributes = attributesDisjunction.run.map {
      _.leftMap { errorMsg =>
        log.error(s"Tried to get Attributes for $identityId but failed with $errorMsg")
        errorMsg
      }.fold(_ => None, identity)
    }

    val adFreeFlagFromDynamo = attributeService.get(identityId) map (_.flatMap(_.AdFree))

    attributes flatMap { maybeAttributes =>
      adFreeFlagFromDynamo map { adFree =>
        maybeAttributes map {_.copy(AdFree = adFree)}
      }
    }
  }

  private def zuoraLookup(endpointDescription: String) =
    backendAction.async { implicit request =>
      authenticationService.userId(request) match {
        case Some(identityId) =>
          attributesFromZuora(identityId, request.touchpoint.patientZuoraRestService, request.touchpoint.subService, request.touchpoint.attrService).map {
            case Some(attrs) =>
              log.info(s"Successfully retrieved attributes from Zuora for user $identityId: $attrs")
              attrs
            case _ => notFound
          }
        case None =>
          metrics.put(s"$endpointDescription-cookie-auth-failed", 1)
          Future(unauthorized)
      }
  }

  val notFound = ApiError("Not found", "Could not find user in the database", 404)
  val notAMember = ApiError("Not found", "User was found but they are not a member", 404)

  private def membershipAttributesFromAttributes(attributes: Attributes): Result = {
     MembershipAttributes.fromAttributes(attributes)
       .map(member => Ok(Json.toJson(member)))
       .getOrElse(notFound)
  }

  def membership = lookup("membership", onSuccessMember = membershipAttributesFromAttributes, onSuccessMemberAndOrContributor = _ => notAMember, onNotFound = notFound, endpointEligibleForTest = true)
  def attributes = lookup("attributes", onSuccessMember = identity[Attributes], onSuccessMemberAndOrContributor = identity[Attributes], onNotFound = notFound, endpointEligibleForTest = true)
  def features = lookup("features", onSuccessMember = Features.fromAttributes, onSuccessMemberAndOrContributor = Features.notAMember, onNotFound = Features.unauthenticated, endpointEligibleForTest = true)
  def zuoraMe = zuoraLookup("zuoraLookup")

  def updateAttributes(identityId : String): Action[AnyContent] = backendForSyncWithZuora.async { implicit request =>

    val tp = request.touchpoint

    val result: EitherT[Future, String, Attributes] =
      // TODO - add the Stripe lookups for the Contribution and Membership cards to this flow, then we can deprecate the Salesforce hook.
      for {
        contact <- EitherT(tp.contactRepo.get(identityId).map(_.flatMap(_ \/> s"No contact for $identityId")))
        memSubF = EitherT[Future, String, Option[Subscription[SubscriptionPlan.Member]]](tp.subService.current[SubscriptionPlan.Member](contact).map(a => \/.right(a.headOption)))
        conSubF = EitherT[Future, String, Option[Subscription[SubscriptionPlan.Contributor]]](tp.subService.current[SubscriptionPlan.Contributor](contact).map(a => \/.right(a.headOption)))
        memSub <- memSubF
        conSub <- conSubF
        _ <- EitherT(Future.successful(if (memSub.isEmpty && conSub.isEmpty) \/.left("No recurring relationship") else \/.right(())))
        attributes = Attributes(
          UserId = identityId,
          Tier = memSub.map(_.plan.charges.benefit.id),
          MembershipNumber = contact.regNumber,
          RecurringContributionPaymentPlan = conSub.map(_.plan.name),
          MembershipJoinDate = memSub.map(_.startDate)
        )
        res <- EitherT(tp.attrService.update(attributes).map(_.disjunction)).leftMap(e => s"Dynamo failed to update the user attributes: ${DynamoReadError.describe(e)}")
      } yield attributes

    result.fold(
      {  error =>
        log.error(s"Failed to update attributes - $error")
        ApiErrors.badRequest(error)
      },
      { attributes =>
        log.info(s"${attributes.UserId} -> ${attributes.Tier} || ${attributes.RecurringContributionPaymentPlan}")
        Ok(Json.obj("updated" -> true))
      }
    )
  }
}
