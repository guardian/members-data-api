package controllers

import com.typesafe.scalalogging.LazyLogging
import configuration.Config
import play.api.libs.json.{JsObject, Json}
import play.api.libs.json.Json.toJson
import play.api.mvc.Controller
import play.filters.cors.CORSActionBuilder
import repositories.BrowserIdStats

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FriendlyTailor extends Controller with LazyLogging {

  def lookup(tags: Seq[String]) = CORSActionBuilder(Config.corsConfig).async { request =>
    require(tags.nonEmpty)
    require(tags.size < 5)

    request.cookies.get("bwid").fold(Future.successful(NotFound("No browser id!"))) { bwidCookie =>
      for {
        pathsByTag <- BrowserIdStats.getPathsByTagFor(bwidCookie.value, tags.toSet)
      } yield Ok(Json.obj("viewedTags" -> toJson(pathsByTag.mapValues(_.size))))
    }
  }
}
