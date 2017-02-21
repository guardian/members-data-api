package models

import json._
import play.api.libs.json._
import play.api.mvc.Result
import play.api.mvc.Results.Ok
import org.joda.time.DateTime

import scala.language.implicitConversions
import play.api.libs.functional.syntax._

case class Behaviour(userId: String, activity: Option[String], lastObserved: Option[String], note: Option[String], email: Option[String], emailed: Option[Boolean])

object Behaviour {

  implicit val jsWrite: OWrites[Behaviour] = (
    (__ \ "userId").write[String] and
    (__ \ "activity").writeNullable[String] and
    (__ \ "lastObserved").writeNullable[String] and
    (__ \ "note").writeNullable[String] and
    (__ \ "email").writeNullable[String] and
    (__ \ "emailed").writeNullable[Boolean]
  )(unlift(Behaviour.unapply))

  implicit def toResult(bhv: Behaviour): Result =
    Ok(asJson(bhv))

  def asJson(bhv: Behaviour) = Json.toJson(bhv)

  def asEmptyJson = asJson(Behaviour("",None,None,None,None,None))

  def empty = Behaviour("",None,None,None,None,None)

}
