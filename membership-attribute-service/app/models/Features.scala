package models

import org.joda.time.LocalDate
import play.api.libs.json.{JsString, Json, Writes}
import play.api.mvc.Result
import play.api.mvc.Results.Ok

import scala.language.implicitConversions

object Features {

  //TODO TEST THAT THE DATE IS CORRECTLY FORMATTED AND REMOVE DUPLICATION
implicit val localDateWrites = new  Writes[LocalDate] {
  override def writes(d: LocalDate) = JsString(d.toString("yyyy-MM-dd"))
}
  implicit val jsWrite = Json.writes[Features]

  implicit def toResult(attrs: Features): Result =
    Ok(Json.toJson(attrs)).withHeaders(
      "X-Gu-Ad-Free" -> attrs.adFree.toString
    )

  def fromAttributes(attributes: Attributes) = {
    Features(
      userId = Some(attributes.UserId),
      adFree = attributes.isAdFree,
      adblockMessage = !attributes.isPaidTier,
      membershipJoinDate = attributes.MembershipJoinDate
    )
  }

  val unauthenticated = Features(None, adFree = false, adblockMessage = true, None)

  def notAMember(attributes: Attributes) = {
    val adFree = attributes.AdFree.getOrElse(false)
    unauthenticated.copy(adFree = adFree)
  }
}

case class Features(
  userId: Option[String],
  adFree: Boolean,
  adblockMessage: Boolean,
  membershipJoinDate: Option[LocalDate]
)
