package controllers
import com.typesafe.scalalogging.LazyLogging
import configuration.Config
import models.ApiError._
import models.ApiErrors._
import models.Features._
import actions._
import models._
import monitoring.CloudWatch
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc.{Controller, Result}
import _root_.services.{AuthenticationService, IdentityAuthService}
import com.gu.memsub.subsv2.SubscriptionPlan
import play.filters.cors.CORSActionBuilder
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.subsv2.reads.ChargeListReads._
import scala.concurrent.Future
import scalaz.{EitherT, \/}
import scalaz.std.scalaFuture._
import scalaz.syntax.std.option._


class AttributeController extends Controller with LazyLogging {

  lazy val corsFilter = CORSActionBuilder(Config.corsConfig)
  lazy val backendAction = corsFilter andThen BackendFromCookieAction
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val metrics = CloudWatch("AttributesController")

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
            onSuccess(attrs)
          case None =>
            onNotFound getOrElse ApiError("Not found", s"User not found in DynamoDB: userId=${id}; stage=${Config.stage}; dynamoTable=${request.touchpoint.dynamoTable}", 404)
        }
      }.getOrElse {
        metrics.put(s"$endpointDescription-cookie-auth-failed", 1)
        Future(unauthorized)
      }
    }

  def membership = lookup("membership", identity[Attributes])
  def features = lookup("features", onSuccess = Features.fromAttributes, onNotFound = Some(Features.unauthenticated))
}
