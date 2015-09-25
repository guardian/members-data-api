package repositories

import com.github.dwhjames.awswrap.dynamodb._
import configuration.Config
import models.MembershipAttributes

object MembershipAttributesDynamo {
  val tableName = Config.dynamoTable

  object Attributes {
    val userId = "UserId"
    val membershipNumber = "MembershipNumber"
    val tier = "Tier"
  }

  implicit object membershipAttributesSerializer
    extends DynamoDBSerializer[MembershipAttributes] {

    override val tableName = MembershipAttributesDynamo.tableName
    override val hashAttributeName = Attributes.userId

    override def primaryKeyOf(membershipAttributes: MembershipAttributes) =
      Map(mkAttribute(Attributes.userId -> membershipAttributes.userId))

    override def toAttributeMap(membershipAttributes: MembershipAttributes) =
      Map(
        mkAttribute(Attributes.userId -> membershipAttributes.userId),
        mkAttribute(Attributes.membershipNumber -> membershipAttributes.membershipNumber.getOrElse("")),
        mkAttribute(Attributes.tier -> membershipAttributes.tier)
      )

    override def fromAttributeMap(item: collection.mutable.Map[String, AttributeValue]) = {
      val numOpt = if (Attributes.membershipNumber.isEmpty) None else Some(Attributes.membershipNumber)

      MembershipAttributes(
        userId = item(Attributes.userId),
        membershipNumber = numOpt.map(item.apply),
        tier = item(Attributes.tier)
      )
    }
  }
}
