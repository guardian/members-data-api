package controllers

import com.gu.identity.play.AuthenticatedIdUser
import com.typesafe.scalalogging.LazyLogging
import controllers.UVAuth._
import play.api.libs.json.Json.toJson
import play.api.mvc.Security.{AuthenticatedBuilder, AuthenticatedRequest}
import play.api.mvc.{ActionBuilder, Controller}
import repositories.Reputation
import services.IdentityAuthService

import scala.concurrent.ExecutionContext.Implicits.global

object UVAuth {

  type AuthRequest[A] = AuthenticatedRequest[A, AuthenticatedIdUser]

  def authenticated(): ActionBuilder[AuthRequest] =
    new AuthenticatedBuilder(IdentityAuthService.playAuthService.authenticatedIdUserProvider)
}

class UserReputation extends Controller with LazyLogging {

  def reputation()  = authenticated().async { idRequest =>
    Reputation.getUserVerification(idRequest.user.id).map(v => Ok(toJson(v)))
  }

  def verifyJustGiving(donationId: Long, returnUrl: String) = authenticated().async { request =>
    // TODO actually get donation status....
    //    val req = new Request.Builder().url(s"http://api.justgiving.com/v1/donation/$donationId").build()
    //    new OkHttpClient().execute(req).map {
    //      resp =>
    //        Json.fromJson(Json.parse(resp.body().byteStream())).get
    //
    //    }

    for {
      _ <- Reputation.putJustGivingDonation(request.user.id, donationId)
    } yield {
        SeeOther(returnUrl)
    }
  }
}
