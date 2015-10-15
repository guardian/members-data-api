package repositories

import com.github.dwhjames.awswrap.dynamodb._
import models.Attributes

object MembershipAttributesSerializer {
  object AttributeNames {
    val userId = "UserId"
    val membershipNumber = "MembershipNumber"
    val tier = "Tier"
  }
}

case class MembershipAttributesSerializer(tableName: String)
  extends DynamoDBSerializer[Attributes] {
  import MembershipAttributesSerializer._

  override val hashAttributeName = AttributeNames.userId

  override def primaryKeyOf(membershipAttributes: Attributes) =
    Map(mkAttribute(AttributeNames.userId -> membershipAttributes.userId))

  override def toAttributeMap(membershipAttributes: Attributes) =
    Map(
      AttributeNames.userId -> membershipAttributes.userId,
      AttributeNames.membershipNumber -> membershipAttributes.membershipNumber.getOrElse(""),
      AttributeNames.tier -> membershipAttributes.tier
    ).filter(_._2.nonEmpty).map(mkAttribute[String])

  override def fromAttributeMap(item: collection.mutable.Map[String, AttributeValue]) =
    Attributes(
      userId = item(AttributeNames.userId),
      membershipNumber = item.get(AttributeNames.membershipNumber).map(_.getS),
      tier = item(AttributeNames.tier)
    )
}
