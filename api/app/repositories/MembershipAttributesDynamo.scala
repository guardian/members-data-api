package repositories

import models.MembershipAttributes
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest
import com.github.dwhjames.awswrap.dynamodb._
import org.joda.time.LocalDate

case class MembershipAttributesDynamo(userId: String, joinDate: LocalDate, tier: String, membershipNumber: String) {

  def toMembershipAttributes: MembershipAttributes =
    MembershipAttributes(joinDate, tier, membershipNumber)
}

object MembershipAttributesDynamo {

  def apply(userId: String, m: MembershipAttributes): MembershipAttributesDynamo =
    MembershipAttributesDynamo(userId, m.joinDate, m.tier, m.membershipNumber)

  val tableName = "MembershipAttributes"

  object Attributes {
    val userId     = "UserId"
    val membershipNumber = "MembershipNumber"
    val tier  = "Tier"
    val joinDate = "JoinDate"
  }

  val tableRequest =
    new CreateTableRequest()
      .withTableName(MembershipAttributesDynamo.tableName)
      .withProvisionedThroughput(
        Schema.provisionedThroughput(10L, 5L))
      .withAttributeDefinitions(
        Schema.stringAttribute(Attributes.userId))
      .withKeySchema(
        Schema.hashKey(Attributes.userId))

  implicit object membershipAttributesSerializer
    extends DynamoDBSerializer[MembershipAttributesDynamo] {

    override val tableName = MembershipAttributesDynamo.tableName
    override val hashAttributeName = Attributes.userId

    override def primaryKeyOf(membershipAttributes: MembershipAttributesDynamo) =
      Map(mkAttribute(Attributes.userId -> membershipAttributes.userId))

    override def toAttributeMap(membershipAttributes: MembershipAttributesDynamo) =
      Map(
        mkAttribute(Attributes.userId     -> membershipAttributes.userId),
        mkAttribute(Attributes.membershipNumber -> membershipAttributes.membershipNumber),
        mkAttribute(Attributes.tier  -> membershipAttributes.tier),
        mkAttribute(Attributes.joinDate -> membershipAttributes.joinDate.toString)
      )

    override def fromAttributeMap(item: collection.mutable.Map[String, AttributeValue]) =
      MembershipAttributesDynamo(
        userId     = item(Attributes.userId),
        membershipNumber = item(Attributes.membershipNumber),
        tier  = item(Attributes.tier),
        joinDate = LocalDate.parse(item(Attributes.joinDate))
      )
  }

}
