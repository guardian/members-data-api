package repositories

import com.github.dwhjames.awswrap.dynamodb._
import models.Attributes
import org.joda.time.LocalDate

object MembershipAttributesSerializer {
  object AttributeNames {
    val userId = "UserId"
    val membershipNumber = "MembershipNumber"
    val tier = "Tier"
    val startDate = "StartDate"
  }
}

case class MembershipAttributesSerializer(tableName: String)
  extends DynamoDBSerializer[Attributes] {
  import MembershipAttributesSerializer._

  override val hashAttributeName = AttributeNames.userId

  override def primaryKeyOf(membershipAttributes: Attributes) =
    Map(mkAttribute(AttributeNames.userId -> membershipAttributes.UserId))

  override def toAttributeMap(membershipAttributes: Attributes) =
    Map(
      AttributeNames.userId -> membershipAttributes.UserId,
      AttributeNames.membershipNumber -> membershipAttributes.MembershipNumber.getOrElse(""),
      AttributeNames.tier -> membershipAttributes.Tier,
      AttributeNames.startDate -> membershipAttributes.StartDate.map(_.toString).getOrElse("")
    ).filter(_._2.nonEmpty).map(mkAttribute[String])

  override def fromAttributeMap(item: collection.mutable.Map[String, AttributeValue]) = {
    Attributes(
      UserId = item(AttributeNames.userId),
      MembershipNumber = item.get(AttributeNames.membershipNumber).map(_.getS),
      Tier = item(AttributeNames.tier),
      StartDate = item.get(AttributeNames.startDate).map(LocalDate.parse(_))
    )
  }
}
