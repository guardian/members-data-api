package controllers
import play.api.data.{Form, Forms}
import play.api.libs.json.Json
import play.api.mvc.{Action, Controller}
import services.{AuthenticationService, IdentityAuthService}
import play.api.libs.concurrent.Execution.Implicits._
import scalaz.syntax.std.option._
import scalaz.std.scalaFuture._
import scala.concurrent.Future
import scalaz.OptionT
import actions._

class TierPublicityController extends Controller {

  lazy val authenticationService: AuthenticationService = IdentityAuthService
  val form = Form(Forms.single("allowPublic" -> Forms.boolean))

  def allowPublic = BackendFromCookieAction.async { implicit r =>
    val service = r.touchpoint.attrService
    (for {
      user <- OptionT(Future.successful(authenticationService.userId))
      formData <- OptionT(Future.successful(form.bindFromRequest.value))
      currentAttributes <- OptionT(service.get(userId = user))
      newAttributes = currentAttributes.copy(publicTierOptIn = formData.some)
      pres <- OptionT(service.set(newAttributes).map(_.some))
    } yield pres).fold(_ => Ok, BadRequest)
  }

  def tierDetails(ids: List[String]) = BackendFromCookieAction.async { r =>
    ids.headOption.fold(Future.successful(Ok(Json.toJson(Map.empty[String, String]))))(_ => r.touchpoint.attrService.getMany(ids).map { attrs =>
      attrs.filter(_.allowsPublicTierDisplay).map(a => a.userId -> a.tier).toMap
    }.map(ids => Ok(Json.toJson(ids))))
  }
}
