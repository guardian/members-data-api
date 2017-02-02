package controllers

import actions._
import com.typesafe.scalalogging.LazyLogging
import configuration.Config
import models.ApiErrors._
import models.{ApiErrors, Behaviour, ApiError, Attributes}
import monitoring.CloudWatch
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json.Json
import play.api.mvc.{Result, Controller}
import play.filters.cors.CORSActionBuilder
import services.{IdentityAuthService, AuthenticationService}
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future
import scalaz.std.scalaFuture._
import scalaz.{EitherT, \/}
import scalaz.syntax.std.option._

class BehaviourController extends Controller with LazyLogging {

  lazy val corsFilter = CORSActionBuilder(Config.corsConfig)
  lazy val backendAction = corsFilter andThen BackendFromCookieAction
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val metrics = CloudWatch("BehaviourController")

  def capture() = BackendFromCookieAction.async { implicit request =>
    val behaviour = request.body.asJson.map { jval =>
      val id = (jval \ "userId").as[String]
      val activity = (jval \ "activity").as[String]
      val dateTime = (jval \ "dateTime").as[String]
      Behaviour(id, activity, DateTime.parse(dateTime).toString(ISODateTimeFormat.dateTime.withZoneUTC))
    }.getOrElse(Behaviour("","",""))

    val result: EitherT[Future, String, Behaviour] = for {
      res <- EitherT(request.touchpoint.behaviourService.set(behaviour).map(\/.right))
    } yield behaviour

    result.run.map(_.fold(
      error => {
        logger.error(s"Failed to update attributes - $error")
        ApiErrors.badRequest(error)
      },
      behaviour => {
        logger.info(s"${behaviour.userId} -> ${behaviour.activity} -> ${behaviour.lastObserved}")
        Ok(Behaviour.asJson(behaviour))
      }
    ))
  }

  def remove = BackendFromCookieAction.async { implicit request =>
    val result: EitherT[Future, String, String] = for {
      id <- EitherT(Future.successful(authenticationService.userId \/> "No user"))
      _ <- EitherT(request.touchpoint.behaviourService.delete(id).map(\/.right))
    } yield id

    result.run.map(_.fold(
      error => {
        logger.error(s"Failed to remove activity for user ${result.getOrElse("!not found!")} - $error")
        ApiErrors.badRequest(error)
      },
      success => {
        logger.info("Recorded activities deleted for user")
        Ok(Behaviour.asEmptyJson)
      }
    ))
  }

}
