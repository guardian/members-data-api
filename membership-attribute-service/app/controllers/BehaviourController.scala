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
import services.IdentityService.IdentityId
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

  def sendCartReminderEmail = backendAction.async { implicit request =>
    val receivedBehaviour = behaviourFromBody(request.body.asJson)
    for {
      paidTier <- request.touchpoint.attrService.get(receivedBehaviour.userId).map(_.exists(_.isPaidTier))
      user <- request.touchpoint.identityService.user(IdentityId(receivedBehaviour.userId))
      emailAddress = (user \ "user" \ "primaryEmailAddress").as[String]
      gnmMarketingPrefs = true // TODO - needs an Identity API PR to send statusFields.receiveGnmMarketing in above user <- ... call
    } yield {
        val msg = if (paidTier || !gnmMarketingPrefs) {
          logger.info(s"### NOT sending email because paidTier: $paidTier gnmMarketingPrefs: $gnmMarketingPrefs")
          request.touchpoint.behaviourService.delete(receivedBehaviour.userId)
          logger.info(s"### deleted reminder record")
          "user has paid or is not accepting emails"
        } else {
          logger.info(s"### sending email to $emailAddress (TESTING ONLY - NO EMAIL IS ACTUALLY GENERATED YET!)")
          // TODO!
          // compile and send the email here
          logger.info(s"### updating behaviour record emailed: true")
          request.touchpoint.behaviourService.set(receivedBehaviour.copy(emailed = Some(true)))
          "email sent - reminder record deleted"
        }
      Ok(msg)
    }
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
      val emailed = (jval \ "email").asOpt[Boolean]
      Behaviour(id, activity, dateTime, note, emailed)
    }.getOrElse(Behaviour.empty)
  }

}
