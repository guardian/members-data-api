package controllers
import play.api.data.{Form, Forms}
import play.api.libs.json.Json
import play.api.mvc.{Action, Controller}
import services.{AttributeService, AuthenticationService, IdentityAuthService}
import play.api.libs.concurrent.Execution.Implicits._

import scalaz.syntax.std.option._
import scalaz.std.scalaFuture._
import scala.concurrent.Future
import scalaz.OptionT
import actions._
import configuration.Config
import play.filters.cors.CORSActionBuilder

class TierPublicityController extends Controller {

  lazy val authenticationService: AuthenticationService = IdentityAuthService
  val form = Form(Forms.single("allowPublic" -> Forms.boolean))

  lazy val publicTierSetCorsFilter = CORSActionBuilder(Config.publicTierSetCorsConfig)
  lazy val publicTierSetAction = publicTierSetCorsFilter andThen BackendFromCookieAction

  lazy val publicTierGetCorsFilter = CORSActionBuilder(Config.publicTierGetCorsConfig)
  lazy val publicTierGetAction = publicTierGetCorsFilter andThen BackendFromCookieAction

  def getPublicTiers(ids: List[String], dynamo: AttributeService): Future[Map[String, String]] =
    ids.headOption.fold(Future.successful(Map.empty[String, String]))(_ => dynamo.getMany(ids).map { attrs =>
      attrs.filter(_.allowsPublicTierDisplay).map(a => a.UserId -> a.Tier).toMap
    })

  def allowPublic = publicTierSetAction.async { implicit r =>
    val service = r.touchpoint.attrService
    (for {
      user <- OptionT(Future.successful(authenticationService.userId))
      formData <- OptionT(Future.successful(form.bindFromRequest.value))
      currentAttributes <- OptionT(service.get(userId = user))
      newAttributes = currentAttributes.copy(PublicTier = formData.some)
      pres <- OptionT(service.set(newAttributes).map(_.some))
    } yield pres).fold(_ => Ok, BadRequest)
  }

  def tierDetails(ids: List[String]) = publicTierGetAction.async { r =>
    getPublicTiers(ids, r.touchpoint.attrService).map(ids => Ok(Json.toJson(ids)))
  }
}
