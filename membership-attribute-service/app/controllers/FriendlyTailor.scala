package controllers

import com.typesafe.scalalogging.LazyLogging
import configuration.Config
import play.api.libs.json.Json
import play.api.mvc.Controller
import play.filters.cors.CORSActionBuilder
import repositories.BrowserIdStats._
import services.IdentityAuthService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class LookupResponse(
  userId: Option[String],
  viewedTags: Map[String,Int]
)

object LookupResponse {
  implicit val writesLookupResponse = Json.writes[LookupResponse]
}

class FriendlyTailor extends Controller with LazyLogging {

  def lookup(tags: Seq[String]) = CORSActionBuilder(Config.ftCorsConfig).async { request =>
    require(tags.nonEmpty)
    require(tags.size < 5)

    val userIdOpt = IdentityAuthService.userId(request)
    val statsByRequestUserId = userIdOpt.map(getStatsForUserId)

    val statsByRequestBrowserId = request.cookies.get("bwid").map(bwidCookie => getStatsForBrowserId(bwidCookie.value))

    val preferredStatsSupplierOpt = Seq(statsByRequestUserId, statsByRequestBrowserId).flatten.headOption

    preferredStatsSupplierOpt.fold(Future.successful(NotFound("No browser or user id!"))) { preferredStatsSupplier =>
      for {
        pathsByTag <- preferredStatsSupplier(tags.toSet)
      } yield Ok(Json.toJson(LookupResponse(userIdOpt, pathsByTag.mapValues(_.size))))
    }
  }
}
