package controllers
import _root_.services.{AuthenticationService, IdentityAuthService}
import actions._
import com.gu.memsub.subsv2.SubscriptionPlan
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
      attributes = Attributes(id, sub.plan.charges.benefit.id, contact.regNumber)
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

  private def lookup(endpointDescription: String, onSuccess: Attributes => Result, onNotFound: Option[Result] = None) = backendAction.async { request =>
      authenticationService.userId(request).map[Future[Result]] { id =>
        request.touchpoint.attrService.get(id).map {
          case Some(attrs) =>
            onSuccess(attrs).withHeaders(
              "X-Gu-Membership-Tier" -> attrs.Tier,
              "X-Gu-Membership-Is-Paid-Tier" -> attrs.isPaidTier.toString
            )
          case None =>
            onNotFound getOrElse ApiError("Not found", s"User not found in DynamoDB: userId=${id}; stage=${Config.stage}; dynamoTable=${request.touchpoint.dynamoAttributesTable}", 404)
        }
      }.getOrElse {
        metrics.put(s"$endpointDescription-cookie-auth-failed", 1)
        Future(unauthorized)
      }
    }

  def membership = lookup("membership", identity[Attributes])
  def features = lookup("features", onSuccess = Features.fromAttributes, onNotFound = Some(Features.unauthenticated))
  
  def updateAttributes(identityId : String): Action[AnyContent] = backendForSyncWithZuora.async { implicit request =>

    val tp = request.touchpoint

    val result: EitherT[Future, String, Attributes] =
      for {
        contact <- EitherT(tp.contactRepo.get(identityId).map(_ \/> s"No contact for $identityId"))
        memSubF = EitherT(tp.subService.current[SubscriptionPlan.Member](contact).map(a => \/.right(a.headOption)))
        conSubF = EitherT(tp.subService.current[SubscriptionPlan.Contributor](contact).map(a => \/.right(a.headOption)))
        memSub <- memSubF
        conSub <- conSubF
        attributes = Attributes( UserId = identityId, Tier = Some(memSub.plan.charges.benefit.id), MembershipNumber = contact.regNumber, Contributor = Some(conSub.plan.charges.benefit.id))
        res <- EitherT(tp.attrService.set(attributes).map(\/.right))
      } yield attributes

    result.run.map(_.fold(
      error => {
        logger.error(s"Failed to update attributes - $error")
        ApiErrors.badRequest(error)
      },
      attributes => {
        logger.info(s"${attributes.UserId} -> ${attributes.Tier} || ${attributes.Contributor}")
        Ok(Json.obj("updated" -> true))
      }
    ))
  }
}



