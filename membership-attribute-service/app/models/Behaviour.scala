package models

import json._
import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.Ok
import org.joda.time.DateTime

import scala.language.implicitConversions
import play.api.libs.functional.syntax._

case class Behaviour(userId: String, activity: String, lastObserved: String, note: String)

object Behaviour {

  implicit val jsWrite: OWrites[Behaviour] = (
    (__ \ "userId").write[String] and
    (__ \ "activity").write[String] and
    (__ \ "lastObserved").write[String] and
    (__ \ "note").write[String]
  )(unlift(Behaviour.unapply))

  implicit def toResult(bhv: Behaviour): Result =
    Ok(asJson(bhv))

  def asJson(bhv: Behaviour) = Json.toJson(bhv)

  def asEmptyJson = asJson(Behaviour("","","",""))

}
