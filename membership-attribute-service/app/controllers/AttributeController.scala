package controllers
import com.gu.memsub._
import configuration.Config
import models.ApiError._
import models.ApiErrors._
import models.Features._
import actions._
import models._
import monitoring.CloudWatch
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc.Result
import _root_.services.{AuthenticationService, IdentityAuthService}
import play.filters.cors.CORSActionBuilder
import scala.concurrent.Future
import scalaz.{\/, EitherT}
import scalaz.std.scalaFuture._
import play.api.mvc.Results.Ok
import scalaz.syntax.std.option._


class AttributeController {

  lazy val corsFilter = CORSActionBuilder(Config.corsConfig)
  lazy val backendAction = corsFilter andThen BackendFromCookieAction
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val metrics = CloudWatch("AttributesController")

  def update() = BackendFromCookieAction.async { implicit request =>
    (for {
      id <- EitherT(Future.successful(authenticationService.userId \/> "No user"))
      contact <- EitherT(request.touchpoint.contactRepo.get(id).map(_ \/> s"No contact for $id"))
      sub <- EitherT(request.touchpoint.membershipSubscriptionService.get(contact)(Membership).map(_ \/> s"No sub for $id"))
      res <- EitherT(request.touchpoint.attrService.set(Attributes(id, sub.plan.tier.name, contact.regNumber)).map(\/.right))
    } yield res).run.map(_.fold(
      error => ApiErrors.badRequest(error),
      _ => Ok(Json.obj("updated" -> true))
    ))
  }

  private def lookup(endpointDescription: String, onSuccess: Attributes => Result, onNotFound: Result = notFound) = backendAction.async { request =>
      authenticationService.userId(request).map[Future[Result]] { id =>
        request.touchpoint.attrService.get(id).map {
          case Some(attrs) =>
            metrics.put(s"$endpointDescription-lookup-successful", 1)
            onSuccess(attrs)
          case None =>
            metrics.put(s"$endpointDescription-user-not-found", 1)
            onNotFound
        }
      }.getOrElse {
        metrics.put(s"$endpointDescription-cookie-auth-failed", 1)
        Future(unauthorized)
      }
    }

  def membership = lookup("membership", identity[Attributes])
  def features = lookup("features", onSuccess = Features.fromAttributes, onNotFound = Features.unauthenticated)
}
