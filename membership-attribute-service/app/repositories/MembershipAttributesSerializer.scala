package repositories

import com.github.dwhjames.awswrap.dynamodb._
import models.MembershipAttributes

object MembershipAttributesSerializer {
  object Attributes {
    val userId = "UserId"
    val membershipNumber = "MembershipNumber"
    val tier = "Tier"
  }
}

case class MembershipAttributesSerializer(tableName: String)
  extends DynamoDBSerializer[MembershipAttributes] {
  import MembershipAttributesSerializer._

  override val hashAttributeName = Attributes.userId

  override def primaryKeyOf(membershipAttributes: MembershipAttributes) =
    Map(mkAttribute(Attributes.userId -> membershipAttributes.userId))

  override def toAttributeMap(membershipAttributes: MembershipAttributes) =
    Map(
      Attributes.userId -> membershipAttributes.userId,
      Attributes.membershipNumber -> membershipAttributes.membershipNumber.getOrElse(""),
      Attributes.tier -> membershipAttributes.tier
    ).filter(_._2.nonEmpty).map(mkAttribute[String])

  override def fromAttributeMap(item: collection.mutable.Map[String, AttributeValue]) =
    MembershipAttributes(
      userId = item(Attributes.userId),
      membershipNumber = item.get(Attributes.membershipNumber).map(_.getS),
      tier = item(Attributes.tier)
    )
}
