package controllers

import actions._
import com.amazonaws.services.dynamodbv2.model.DeleteItemResult
import com.typesafe.scalalogging.LazyLogging
import configuration.Config
import models.{ApiErrors, Behaviour}
import monitoring.Metrics
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AnyContent, Result, Controller}
import services.{IdentityAuthService, AuthenticationService}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.Controller
import play.filters.cors.CORSActionBuilder

class BehaviourController extends Controller with LazyLogging {

  lazy val corsFilter = CORSActionBuilder(Config.corsConfig)
  lazy val backendAction = corsFilter andThen BackendFromCookieAction
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val metrics = Metrics("BehaviourController")

  def capture() = BackendFromCookieAction.async { implicit request =>
    awsAction(request, "add")
  }

  def remove = BackendFromCookieAction.async { implicit request =>
    awsAction(request, "delete")
  }

  private def awsAction(request: BackendRequest[AnyContent], action: String) = {
    val behaviour = behaviourFromBody(request.body.asJson)
    action match {
      case "add" =>
        val addResult = for {
          addItemResult <- request.touchpoint.behaviourService.set(behaviour)
        } yield addItemResult
        addResult.map { r =>
          logger.info(s"recorded ${behaviour.activity} on ${behaviour.lastObserved} for ${behaviour.userId}")
          Ok(Behaviour.asJson(behaviour))
        }
      case _ =>
        val deleteResult = for {
          deleteItemResult <- request.touchpoint.behaviourService.delete(behaviour.userId)
        } yield deleteItemResult
        deleteResult.map { r =>
          logger.info(s"removed ${behaviour.activity} for ${behaviour.userId}")
          Ok(Behaviour.asEmptyJson)
        }
    }
  }

  private def behaviourFromBody(requestBodyJson: Option[JsValue]): Behaviour = {
    requestBodyJson.map { jval =>
      val id = (jval \ "userId").as[String]
      val activity = (jval \ "activity").as[String]
      val dateTime = (jval \ "dateTime").as[String]
      Behaviour(id, activity, DateTime.parse(dateTime).toString(ISODateTimeFormat.dateTime.withZoneUTC))
    }.getOrElse(Behaviour("","",""))
  }

}
