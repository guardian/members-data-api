package controllers
import _root_.services.{AuthenticationService, IdentityAuthService}
import actions._
import com.gu.memsub.subsv2.{Subscription, SubscriptionPlan}
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.typesafe.scalalogging.LazyLogging
import configuration.Config
import models.ApiError._
import models.ApiErrors._
import models.Features._
import models._
import monitoring.Metrics
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc._
import play.filters.cors.CORSActionBuilder

import scala.concurrent.{Future, Promise}
import scalaz.std.scalaFuture._
import scalaz.syntax.std.option._
import scalaz.{EitherT, \/}
import configuration.Config.authentication


class AttributeController extends Controller with LazyLogging {

  def signed(): ActionBuilder[Request] = new ActionBuilder[Request] {
    def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {
      if (request.headers.get("authentication").contains(authentication.key)) {
        block(request)
      } else {
        Future.successful(Forbidden)
      }
    }
  }

  lazy val corsFilter = CORSActionBuilder(Config.corsConfig)
  lazy val backendAction = NoCacheAction andThen corsFilter andThen BackendFromCookieAction
  lazy val backendForSyncWithZuora = NoCacheAction andThen  signed andThen WithBackendFromUserIdAction
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val metrics = Metrics("AttributesController")

  def update = BackendFromCookieAction.async { implicit request =>

    val result: EitherT[Future, String, Attributes] = for {
      id <- EitherT(Future.successful(authenticationService.userId \/> "No user"))
      contact <- EitherT(request.touchpoint.contactRepo.get(id).map(_ \/> s"No contact for $id"))
      sub <- EitherT(request.touchpoint.subService.current[SubscriptionPlan.Member](contact).map(_.headOption \/> s"No sub for $id"))
      attributes = Attributes(id, Some(sub.plan.charges.benefit.id), contact.regNumber)
      res <- EitherT(request.touchpoint.attrService.set(attributes).map(\/.right))
    } yield attributes

    result.run.map(_.fold(
      error => {
        logger.error(s"Failed to update attributes - $error")
        ApiErrors.badRequest(error)
      },
      attributes => {
        logger.info(s"${attributes.UserId} -> ${attributes.Tier}")
        Ok(Json.obj("updated" -> true))
      }
    ))
  }

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
            logger.info(s"$id was not found - $endpointDescription")
            onNotFound
        }
      }.getOrElse {
        metrics.put(s"$endpointDescription-cookie-auth-failed", 1)
        Future(unauthorized)
      }
    }


  val notFound = ApiError("Not found", "Could not find user in the database", 404)
  val notAMember = ApiError("Not found", "User was found but they are not a member", 404)

  def membership = lookup("membership", onSuccessMember = identity[Attributes], onSuccessMemberAndOrContributor = _ => notAMember, onNotFound = notFound)
  def attributes = lookup("attributes", onSuccessMember = identity[Attributes], onSuccessMemberAndOrContributor = identity[Attributes], onNotFound = notFound)
  def features = lookup("features", onSuccessMember = Features.fromAttributes, onSuccessMemberAndOrContributor = _ => Features.unauthenticated, onNotFound = Features.unauthenticated)


  def updateAttributes(identityId : String): Action[AnyContent] = backendForSyncWithZuora.async { implicit request =>

    val tp = request.touchpoint

    val result: EitherT[Future, String, Attributes] =
      for {
        contact <- EitherT(tp.contactRepo.get(identityId).map(_ \/> s"No contact for $identityId"))
        memSubF = EitherT[Future, String, Option[Subscription[SubscriptionPlan.Member]]](tp.subService.current[SubscriptionPlan.Member](contact).map(a => \/.right(a.headOption)))
        conSubF = EitherT[Future, String, Option[Subscription[SubscriptionPlan.Contributor]]](tp.subService.current[SubscriptionPlan.Contributor](contact).map(a => \/.right(a.headOption)))
        memSub <- memSubF
        conSub <- conSubF
        _ <- EitherT(Future.successful(if (memSub.isEmpty && conSub.isEmpty) \/.left("No paying relationship") else \/.right(())))
        attributes = Attributes( UserId = identityId, Tier = memSub.map(_.plan.charges.benefit.id), MembershipNumber = contact.regNumber, ContributionFrequency = conSub.map(_.plan.name))
        res <- EitherT(tp.attrService.update(attributes).map(\/.right))
      } yield attributes

    result.run.map(_.fold(
      error => {
        logger.error(s"Failed to update attributes - $error")
        ApiErrors.badRequest(error)
      },
      attributes => {
        logger.info(s"${attributes.UserId} -> ${attributes.Tier} || ${attributes.ContributionFrequency}")
        Ok(Json.obj("updated" -> true))
      }
    ))
  }
}



