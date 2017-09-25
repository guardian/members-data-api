package controllers

import actions.NoCacheAction
import com.gu.stripe.Stripe._
import com.typesafe.scalalogging.LazyLogging
import components.{NormalTouchpointComponents, TestTouchpointComponents}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.mvc._

import scala.concurrent.Future
import scalaz.OptionT
import scalaz.std.scalaFuture._
import com.gu.stripe.Stripe.Deserializer._


class StripeHookController extends Controller with LazyLogging {

  // TODO - confirm if these are deprecated and the class can be deleted.
  // Right now I'm leaving it hardcoded to the uk contributions stripe service.

  def updatePrefs = NoCacheAction.async { implicit request =>
    request.body.asJson.map(Json.fromJson[Event[StripeObject]](_)).fold[Future[Result]] {
      Future.successful(BadRequest("No JSON found in request body"))
    } { event =>
      (for {
        e <- OptionT(Future.successful(event.asOpt))
        tp = if (e.liveMode) NormalTouchpointComponents else TestTouchpointComponents
        eventFromStripe <- OptionT(tp.ukContributionsStripeService.Event.findCharge(e.id))
        identityId <- OptionT(tp.identityService.user(eventFromStripe.`object`.receipt_email))
        allowMarketing <- OptionT(Future.successful(eventFromStripe.`object`.metadata.get("marketing-opt-in").map(_ == "true")))
      } yield {
        tp.identityService.setMarketingPreference(identityId, allowMarketing)
        logger.info(s"$identityId marketing -> $allowMarketing")
      }).run
        .map(_.fold[Result](Ok(Json.obj("updated" -> false)))
          (_ => Ok(Json.obj("updated" -> true))))
    }
  }


  def publishToSns = NoCacheAction.async { implicit request =>
    request.body.asJson.map(Json.fromJson[Event[StripeObject]](_)).fold[Future[Result]] {
      Future.successful(BadRequest("No JSON found in request body"))
    } { event =>
      (for {
        e <- OptionT(Future.successful(event.asOpt))
        tp = if (e.liveMode) NormalTouchpointComponents else TestTouchpointComponents
        eventFromStripe <- OptionT(tp.ukContributionsStripeService.Event.findCharge(e.id))
        balanceTransaction <- OptionT(tp.ukContributionsStripeService.BalanceTransaction.read(eventFromStripe.`object`.balance_transaction.mkString))
      } yield {
        tp.snsGiraffeService.publish(eventFromStripe.`object`, balanceTransaction)
        Ok(Json.obj("event " + eventFromStripe.id + " was found, sent to " + tp.giraffeSns -> true))
      }).getOrElse(Ok(Json.obj("event found" -> false)))
    }
  }

}

