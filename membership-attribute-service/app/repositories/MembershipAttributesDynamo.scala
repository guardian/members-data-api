package repositories

import com.github.dwhjames.awswrap.dynamodb._
import configuration.Config
import models.MembershipAttributes

case class MembershipAttributesDynamo(userId: String, tier: String, membershipNumber: String) {

  def toMembershipAttributes: MembershipAttributes =
    MembershipAttributes(tier, membershipNumber)
}

object MembershipAttributesDynamo {

  def apply(userId: String, m: MembershipAttributes): MembershipAttributesDynamo =
    MembershipAttributesDynamo(userId, m.tier, m.membershipNumber)

  val tableName = Config.dynamoTable

  object Attributes {
    val userId = "UserId"
    val membershipNumber = "MembershipNumber"
    val tier = "Tier"
    val joinDate = "JoinDate"
  }

  implicit object membershipAttributesSerializer
    extends DynamoDBSerializer[MembershipAttributesDynamo] {

    override val tableName = MembershipAttributesDynamo.tableName
    override val hashAttributeName = Attributes.userId

    override def primaryKeyOf(membershipAttributes: MembershipAttributesDynamo) =
      Map(mkAttribute(Attributes.userId -> membershipAttributes.userId))

    override def toAttributeMap(membershipAttributes: MembershipAttributesDynamo) =
      Map(
        mkAttribute(Attributes.userId -> membershipAttributes.userId),
        mkAttribute(Attributes.membershipNumber -> membershipAttributes.membershipNumber),
        mkAttribute(Attributes.tier -> membershipAttributes.tier)
      )

    override def fromAttributeMap(item: collection.mutable.Map[String, AttributeValue]) =
      MembershipAttributesDynamo(
        userId = item(Attributes.userId),
        membershipNumber = item(Attributes.membershipNumber),
        tier = item(Attributes.tier)
      )
  }

}
