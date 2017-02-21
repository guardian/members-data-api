package controllers

import actions._
import com.typesafe.scalalogging.LazyLogging
import configuration.Config
import models.Behaviour
import monitoring.Metrics
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.JsValue
import play.api.mvc.{AnyContent, Controller}
import play.filters.cors.CORSActionBuilder
import services.{AuthenticationService, IdentityAuthService}

class BehaviourController extends Controller with LazyLogging {

  lazy val corsFilter = CORSActionBuilder(Config.corsConfig)
  lazy val backendAction = corsFilter andThen BackendFromCookieAction
  lazy val authenticationService: AuthenticationService = IdentityAuthService
  lazy val metrics = Metrics("BehaviourController")

  def capture() = BackendFromCookieAction.async { implicit request =>
    awsAction(request, "upsert")
  }

  def remove = BackendFromCookieAction.async { implicit request =>
    awsAction(request, "delete")
  }

  private def awsAction(request: BackendRequest[AnyContent], action: String) = {
    val receivedBehaviour = behaviourFromBody(request.body.asJson)
    action match {
      case "upsert" =>
        val updateResult = for {
          current <- request.touchpoint.behaviourService.get(receivedBehaviour.userId)
          upserted = current.map { bhv =>
            bhv.copy(
              activity = receivedBehaviour.activity.orElse(bhv.activity),
              lastObserved = receivedBehaviour.lastObserved.orElse(bhv.lastObserved),
              note = receivedBehaviour.note.orElse(bhv.note),
              email = receivedBehaviour.email.orElse(bhv.email),
              emailed = receivedBehaviour.emailed.orElse(bhv.emailed))
          }.getOrElse(receivedBehaviour)
          res <- request.touchpoint.behaviourService.set(upserted)
        } yield res
        updateResult.map { r =>
          logger.info(s"upserted ${receivedBehaviour.userId}")
          Ok(Behaviour.asEmptyJson)
        }
      case _ =>
        val deleteResult = for {
          deleteItemResult <- request.touchpoint.behaviourService.delete(receivedBehaviour.userId)
        } yield deleteItemResult
        deleteResult.map { r =>
          logger.info(s"removed ${receivedBehaviour.userId}")
          Ok(Behaviour.asEmptyJson)
        }
    }
  }

  private def behaviourFromBody(requestBodyJson: Option[JsValue]): Behaviour = {
    requestBodyJson.map { jval =>
      val id = (jval \ "userId").as[String]
      val activity = (jval \ "activity").asOpt[String]
      val dateTime = (jval \ "dateTime").asOpt[String]
      val note = (jval \ "note").asOpt[String]
      val email = (jval \ "email").asOpt[String]
      val emailed = (jval \ "email").asOpt[Boolean]
      Behaviour(id, activity, dateTime, note, email, emailed)
    }.getOrElse(Behaviour.empty)
  }

}
