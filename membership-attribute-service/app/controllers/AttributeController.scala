package controllers
import actions._
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.subsv2.services.SubscriptionService
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.zuora.ZuoraRestService
import com.gu.zuora.ZuoraRestService.QueryResponse
import com.typesafe.scalalogging.LazyLogging
import configuration.Config
import configuration.Config.authentication
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
import services.{AttributesMaker, AuthenticationService, IdentityAuthService}

import scala.concurrent.Future
import scalaz.std.scalaFuture._
import scalaz.syntax.std.option._
import scalaz.{EitherT, \/, _}

class AttributeController extends Controller with LazyLogging {

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


  private def lookup(endpointDescription: String, onSuccessMember: Attributes => Result, onSuccessMemberAndOrContributor: Attributes => Result, onNotFound: Result) = backendAction.async { request =>
      authenticationService.userId(request).map[Future[Result]] { id =>
        request.touchpoint.attrService.get(id).map {
          case Some(attrs @ Attributes(_, Some(tier), _, _, _, _, _)) =>
            logger.info(s"$id is a member - $endpointDescription - $attrs")
            onSuccessMember(attrs).withHeaders(
              "X-Gu-Membership-Tier" -> tier,
              "X-Gu-Membership-Is-Paid-Tier" -> attrs.isPaidTier.toString
            )
          case Some(attrs) =>
            logger.info(s"$id is a contributor - $endpointDescription - $attrs")
            onSuccessMemberAndOrContributor(attrs)
          case _ =>
            onNotFound
        }
      }.getOrElse {
        metrics.put(s"$endpointDescription-cookie-auth-failed", 1)
        Future(unauthorized)
      }
    }

  private def attributesFromZuora(id: String, zuoraRestService: ZuoraRestService[Future], subscriptionService: SubscriptionService[Future]): Future[Option[Attributes]] = {
    def queryToAccountIds(response: QueryResponse): List[AccountId] =  response.records.map(_.Id)

    def getSubscriptions(accountIds: List[AccountId]): Future[List[Subscription[AnyPlan]]] = {
      def sub(accountId: AccountId): Future[List[Subscription[AnyPlan]]] = {
        subscriptionService.subscriptionsForAccountId[AnyPlan](accountId)(anyPlanReads)
      }
      Future.traverse(accountIds)(id => sub(id)).map(_.flatten)
    }

    val attributes: DisjunctionT[Future, String, Option[Attributes]] = for {
      accounts <- EitherT(zuoraRestService.getAccounts(id))
      accountIds = queryToAccountIds(accounts)
      subscriptions <- EitherT[Future, String, List[Subscription[AnyPlan]]](getSubscriptions(accountIds).map(a => \/.right(a)))
    } yield {
      AttributesMaker.attributes(id, subscriptions, LocalDate.now())
    }

    attributes.run.map(_.toOption).map(_.getOrElse(None))
  }

  private def zuoraLookup(endpointDescription: String) =
    backendAction.async { request =>
      authenticationService.userId(request) match {
        case Some(id) =>
          attributesFromZuora(id, request.touchpoint.zuoraRestService, request.touchpoint.subService).map {
            case Some(attrs) =>
              logger.info(s"$id is a contributor - $endpointDescription - $attrs")
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

  def membership = lookup("membership", onSuccessMember = membershipAttributesFromAttributes, onSuccessMemberAndOrContributor = _ => notAMember, onNotFound = notFound)
  def attributes = lookup("attributes", onSuccessMember = identity[Attributes], onSuccessMemberAndOrContributor = identity[Attributes], onNotFound = notFound)
  def features = lookup("features", onSuccessMember = Features.fromAttributes, onSuccessMemberAndOrContributor = _ => Features.unauthenticated, onNotFound = Features.unauthenticated)
  def zuoraMe = zuoraLookup("zuoraLookup")

  def updateAttributes(identityId : String): Action[AnyContent] = backendForSyncWithZuora.async { implicit request =>

    val tp = request.touchpoint

    val result: EitherT[Future, String, Attributes] =
      // TODO - add the Stripe lookups for the Contribution and Membership cards to this flow, then we can deprecate the Salesforce hook.
      for {
        contact <- EitherT(tp.contactRepo.get(identityId).map(_ \/> s"No contact for $identityId"))
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
        res <- EitherT(tp.attrService.update(attributes).map(\/.right))
      } yield attributes

    result.fold(
      {  error =>
        logger.error(s"Failed to update attributes - $error")
        ApiErrors.badRequest(error)
      },
      { attributes =>
        logger.info(s"${attributes.UserId} -> ${attributes.Tier} || ${attributes.RecurringContributionPaymentPlan}")
        Ok(Json.obj("updated" -> true))
      }
    )
  }
}
