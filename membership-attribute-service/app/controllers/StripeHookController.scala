package controllers

import com.gu.stripe.Stripe
import com.typesafe.scalalogging.LazyLogging
import play.api.libs.concurrent.Execution.Implicits._
import com.gu.stripe.Stripe._
import com.gu.stripe.Stripe.Deserializer._
import components.{NormalTouchpointComponents, TestTouchpointComponents}

import scalaz.std.scalaFuture._
import play.api.libs.json.Json
import play.api.mvc._

import scala.concurrent.Future
import scalaz.OptionT


class StripeHookController extends Controller with LazyLogging {

  def updatePrefs = Action.async { implicit request =>
    request.body.asJson.map(Json.fromJson[Event[StripeObject]](_)).fold[Future[Result]] {
      Future.successful(BadRequest("No JSON found in request body"))
    } { event =>
      (for {
        e <- OptionT(Future.successful(event.asOpt))
        tp = if (e.liveMode) NormalTouchpointComponents else TestTouchpointComponents
        eventFromStripe <- OptionT(tp.giraffeStripeService.Event.findCharge(e.id))
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


  def publishToSns = Action.async { implicit request =>
    request.body.asJson.map(Json.fromJson[Event[StripeObject]](_)).fold[Future[Result]] {
      Future.successful(BadRequest("No JSON found in request body"))
    } { event =>
      (for {
        e <- OptionT(Future.successful(event.asOpt))
        tp = if (e.liveMode) NormalTouchpointComponents else TestTouchpointComponents
        eventFromStripe <- OptionT(tp.giraffeStripeService.Event.findCharge(e.id))
        eventWithConvertedAmountAndCountry <- OptionT(tp.giraffeStripeService.BalanceTransaction.read(eventFromStripe.`object`))
      } yield {
        tp.snsGiraffeService.publish(eventWithConvertedAmountAndCountry)
        Ok(Json.obj("event " + eventFromStripe.id + " found, sent to " + tp.giraffeSns -> true))
      }).getOrElse(Ok(Json.obj("event found" -> false)))
    }
  }

}

