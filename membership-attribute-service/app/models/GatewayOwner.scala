package models

sealed trait GatewayOwner
object GatewayOwner {
  case object TortoiseMedia extends GatewayOwner
  case object Default extends GatewayOwner

  def fromString(value: String): Option[GatewayOwner] = value.toLowerCase match {
    case "tortoise-media" => Some(TortoiseMedia)
    case _ => Some(Default)
  }
}
