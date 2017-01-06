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

  def capture(activity: String) = BackendFromCookieAction.async { implicit request =>
    val result: EitherT[Future, String, Behaviour] = for {
      id <- EitherT(Future.successful(authenticationService.userId \/> "No user"))
      behaviour = Behaviour(id, activity, DateTime.now.toString(ISODateTimeFormat.dateTime.withZoneUTC))
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
        logger.info(s"Recorded activities deleted for user ${result.getOrElse("!not found!")}")
        Ok(Json.obj("captured" -> true))
      }
    ))
  }

  def behaviours = lookup("behaviours")

  private def lookup(endpointDescription: String) = backendAction.async { request =>
    authenticationService.userId(request).map[Future[Result]] { id =>
      request.touchpoint.behaviourService.get(id).map {
        case Some(bhv) =>
          Behaviour.toResult(bhv)
        case None =>
          logger.error(s"User not found in DynamoDB: userId=${id}; stage=${Config.stage}; dynamoTable=${request.touchpoint.dynamoBehaviourTable}")
          ApiError("Not found", s"User not found in DynamoDB: userId=${id}; stage=${Config.stage}; dynamoTable=${request.touchpoint.dynamoBehaviourTable}", 404)
      }
    }.getOrElse {
      logger.info(s"$endpointDescription-cookie-auth-failed")
      metrics.put(s"$endpointDescription-cookie-auth-failed", 1)
      Future(unauthorized)
    }
  }


}
