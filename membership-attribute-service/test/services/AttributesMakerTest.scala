package services

import com.github.nscala_time.time.Implicits._
import models.{Attributes, DynamoAttributes, ZuoraAttributes}
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import testdata.SubscriptionTestData

class AttributesMakerTest extends Specification with SubscriptionTestData {
  override def referenceDate = new LocalDate(2016, 10, 26)
  val referenceDateAsDynamoTimestamp = referenceDate.toDateTimeAtStartOfDay.getMillis / 1000


  "zuoraAttributes" should {
    val testId = "123"

    "return attributes when digipack sub" in {
      val expected = Some(ZuoraAttributes(
        UserId = testId,
        Tier = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = None,
        DigitalSubscriptionExpiryDate = Some(referenceDate + 1.year)
      ))
      AttributesMaker.zuoraAttributes(testId, List(digipack), referenceDate) === expected
    }

    "return attributes when one of the subs has a digital benefit" in {
      val expected = Some(ZuoraAttributes(
        UserId = testId,
        Tier = None,
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = None,
        DigitalSubscriptionExpiryDate = Some(referenceDate + 1.year)
      ))
      AttributesMaker.zuoraAttributes(testId, List(sunday, sundayPlus), referenceDate) === expected
    }

    "return none when only sub is expired" in {
      AttributesMaker.zuoraAttributes(testId, List(expiredMembership), referenceDate) === None
    }

    "return attributes when there is one membership sub" in {
      val expected = Some(ZuoraAttributes(
        UserId = testId,
        Tier = Some("Supporter"),
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = Some(referenceDate)
      )
      )
      AttributesMaker.zuoraAttributes(testId, List(membership), referenceDate) === expected
    }

    "return attributes when one sub is expired and one is not" in {
      val expected = Some(ZuoraAttributes(
        UserId = testId,
        Tier = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = None
      )
      )

      AttributesMaker.zuoraAttributes(testId, List(expiredMembership, contributor), referenceDate) === expected
    }

    "return attributes when one sub is a recurring contribution" in {
      val expected = Some(ZuoraAttributes(
        UserId = testId,
        Tier = None,
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = None
      )
      )
      AttributesMaker.zuoraAttributes(testId, List(contributor), referenceDate) === expected
    }

    "return attributes relevant to both when one sub is a contribution and the other a membership" in {
      val expected = Some(ZuoraAttributes(
        UserId = testId,
        Tier = Some("Supporter"),
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = Some(referenceDate)
      )
      )
      AttributesMaker.zuoraAttributes(testId, List(contributor, membership), referenceDate) === expected
    }

    "return attributes when the membership is a friend tier" in {
      val expected = Some(ZuoraAttributes(
        UserId = testId,
        Tier = Some("Friend"),
        RecurringContributionPaymentPlan = None,
        MembershipJoinDate = Some(referenceDate)
      )
      )
      AttributesMaker.zuoraAttributes(testId, List(friend), referenceDate) === expected
    }

    "attributes" should  {
      "return up to date Zuora attributes when they match the dynamo attributes" in {
        val zuoraAttributes = ZuoraAttributes(
          UserId = testId,
          Tier = None,
          RecurringContributionPaymentPlan = Some("Monthly Contribution"),
          MembershipJoinDate = None,
          DigitalSubscriptionExpiryDate = None
        )

        val dynamoAttributes = DynamoAttributes(
          UserId = testId,
          Tier = None,
          RecurringContributionPaymentPlan = Some("Monthly Contribution"),
          MembershipJoinDate = None,
          DigitalSubscriptionExpiryDate = None,
          MembershipNumber = None,
          AdFree = None,
          TTLTimestamp = referenceDateAsDynamoTimestamp
        )

        val expected = Some(
          Attributes(
            UserId = testId,
            Tier = None,
            RecurringContributionPaymentPlan = Some("Monthly Contribution"),
            MembershipJoinDate = None,
            DigitalSubscriptionExpiryDate = None
          )
        )

        val attributes = AttributesMaker.zuoraAttributesWithAddedDynamoFields(Some(zuoraAttributes), Some(dynamoAttributes))

        attributes === expected
      }

      "still return Zuora attributes when the user is not in Dynamo" in {
        val zuoraAttributes = ZuoraAttributes(
          UserId = testId,
          Tier = None,
          RecurringContributionPaymentPlan = Some("Monthly Contribution"),
          MembershipJoinDate = None,
          DigitalSubscriptionExpiryDate = None
        )

        val expected = Some(
          Attributes(
            UserId = testId,
            Tier = None,
            RecurringContributionPaymentPlan = Some("Monthly Contribution"),
            MembershipJoinDate = None,
            DigitalSubscriptionExpiryDate = None
          )
        )

        val attributes = AttributesMaker.zuoraAttributesWithAddedDynamoFields(Some(zuoraAttributes), None)

        attributes === expected
      }

      "return none if both Dynamo and Zuora attributes are none" in {
        AttributesMaker.zuoraAttributesWithAddedDynamoFields(None, None) === None
      }
    }
  }
}

