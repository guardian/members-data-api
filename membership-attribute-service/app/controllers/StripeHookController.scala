package controllers

import com.typesafe.scalalogging.LazyLogging
import play.api.libs.concurrent.Execution.Implicits._
import com.gu.salesforce.ContactDeserializer.Keys
import com.gu.stripe.Stripe._
import com.gu.stripe.Stripe.Deserializer._
import components.{TestTouchpointComponents, NormalTouchpointComponents}
import scalaz.std.scalaFuture._
import play.api.libs.json.Json
import play.api.mvc.Results._
import play.api.mvc._
import scala.concurrent.Future
import scalaz.OptionT


class StripeHookController extends Controller with LazyLogging {

  def process = Action.async { implicit request =>
    request.body.asJson.map(Json.fromJson[Event[StripeObject]](_)).fold[Future[Result]] {
      Future.successful(BadRequest("No JSON found in request body"))
    } { event =>
      (for {
        e <- OptionT(Future.successful(event.asOpt))
        tp = if(e.liveMode) NormalTouchpointComponents else TestTouchpointComponents
        eventFromStripe <- OptionT(tp.giraffeStripeService.Event.findCharge(e.id))
        contact <- OptionT(tp.contactRepo.getByEmail(eventFromStripe.`object`.receipt_email))
        allowMarketing <- OptionT(Future.successful(eventFromStripe.`object`.metadata.get("marketing-opt-in").map(_ == "true")))
      } yield {
        tp.contactRepo.upsert(contact.identityId, Json.obj(Keys.ALLOW_GU_RELATED_MAIL -> allowMarketing))
        logger.info(s"${contact.identityId} marketing -> $allowMarketing")
      }).run
      .map(_.fold[Result](BadRequest)(_ => Ok(Json.obj("success" -> true))))
    }
  }
}
