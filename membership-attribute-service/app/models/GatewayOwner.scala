package models

sealed abstract class GatewayOwner(val value: Option[String])
object GatewayOwner {
  case object TortoiseMedia extends GatewayOwner(Some("tortoise-media"))
  case object Default extends GatewayOwner(None)

  def fromString(value: Option[String]): GatewayOwner = {
    value.map(_.toLowerCase) match {
      case Some("tortoise-media") => TortoiseMedia
      case _ => Default
    }
  }
}
