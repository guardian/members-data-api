package services

import models.SupporterRatePlanItem
import org.joda.time.{DateTime, LocalDate}
import org.specs2.mutable.Specification

class SupporterRatePlanToAttributesMapperTest extends Specification {
  val mapper = new SupporterRatePlanToAttributesMapper("PROD")
  val identityId = "999"
  val termEndDate = LocalDate.now().plusDays(5)
  def ratePlanItem(ratePlanId: String) = SupporterRatePlanItem(
    identityId,
    "some-rate-plan-id",
    ratePlanId,
    termEndDate
  )

  "Mapper" should {
    "identify a monthly contribution" in {
      val attributes = mapper.attributesFromSupporterRatePlans(
        identityId,
        List(ratePlanItem("2c92a0fc5aacfadd015ad24db4ff5e97"))
      )
      attributes.RecurringContributionPaymentPlan must beSome("Monthly")
    }

    "identify an annual contribution" in {
      val attributes = mapper.attributesFromSupporterRatePlans(
        identityId,
        List(ratePlanItem("2c92a0fc5e1dc084015e37f58c200eea"))
      )
      attributes.RecurringContributionPaymentPlan must beSome("Annual")
    }

    "identify a Digital Subscription" in {
      val possibleProductRatePlanIds = List(
        "2c92a0fb4edd70c8014edeaa4eae220a", // Monthly
        "2c92a0fb4edd70c8014edeaa4e972204", // Annual
        "2c92a00d71c96bac0171df3a5622740f", // Corporate
        "2c92a00d779932ef0177a65430d30ac1", // Three Month Gift
        "2c92a00c77992ba70177a6596f710265" // One Year Gift
      )
      possibleProductRatePlanIds.map(productRatePlanId =>
        mapper
          .attributesFromSupporterRatePlans(
            identityId,
            List(ratePlanItem(productRatePlanId))
          )
          .latestDigitalSubscriptionExpiryDate must beSome(termEndDate)
      )
    }

    "identify a Guardian Weekly" in {
      val possibleProductRatePlanIds = List(
        "2c92a0fe6619b4b601661ab300222651", // annual, rest of world delivery
        "2c92a0ff67cebd140167f0a2f66a12eb", // one year, rest of world deliver
        "2c92a0086619bf8901661ab02752722f", // quarterly, rest of world delivery
        "2c92a0076dd9892e016df8503e7c6c48", // three month, rest of world deliver
        "2c92a0fe6619b4b901661aa8e66c1692", // annual, domestic delivery")
        "2c92a0ff67cebd0d0167f0a1a834234e", // one year, domestic delivery"
        "2c92a0fe6619b4b301661aa494392ee2", // quarterly, domestic delivery")
        "2c92a00e6dd988e2016df85387417498" // three months, domestic delivery
      )
      possibleProductRatePlanIds.map(productRatePlanId =>
        mapper
          .attributesFromSupporterRatePlans(
            identityId,
            List(ratePlanItem(productRatePlanId))
          )
          .GuardianWeeklySubscriptionExpiryDate must beSome(termEndDate)
      )
    }

    "identify a Paper sub" in {
      val possibleProductRatePlanIds = List(
        // digital voucher
        "2c92a00870ec598001710740cdd02fbd", //Saturday
        "2c92a00870ec598001710740d0d83017", //Sunday
        "2c92a00870ec598001710740d24b3022", //Weekend
        "2c92a00870ec598001710740ca532f69", //Sixday
        "2c92a00870ec598001710740c78d2f13", //Everyday
        // voucher book
        "2c92a0fd6205707201621f9f6d7e0116", //Saturday
        "2c92a0fe5af9a6b9015b0fe1ecc0116c", //Sunday
        "2c92a0ff56fe33f00157040f9a537f4b", //Weekend
        "2c92a0fd56fe270b0157040e42e536ef", //Sixday
        "2c92a0fd56fe270b0157040dd79b35da", //Everyday
        // Home delivery
        "2c92a0fd5e1dcf0d015e3cb39d0a7ddb", //Saturday"
        "2c92a0ff5af9b657015b0fea5b653f81", //Sunday"
        "2c92a0fd5614305c01561dc88f3275be", //Weekend"
        "2c92a0ff560d311b0156136f2afe5315", //Sixday"
        "2c92a0fd560d13880156136b72e50f0c" //Everyday"
      )
      possibleProductRatePlanIds.map(productRatePlanId =>
        mapper
          .attributesFromSupporterRatePlans(
            identityId,
            List(ratePlanItem(productRatePlanId))
          )
          .PaperSubscriptionExpiryDate must beSome(termEndDate)
      )
    }

    "identify a Paper plus digital sub" in {
      val possibleProductRatePlanIds = List(
        // digital voucher
        "2c92a00870ec598001710740ce702ff0", //Voucher Saturday+
        "2c92a00870ec598001710740cf9e3004", //Voucher Sunday+
        "2c92a00870ec598001710740c6672ee7", //Voucher Weekend+
        "2c92a00870ec598001710740c4582ead", //Voucher Sixday+
        "2c92a00870ec598001710740d3d03035", //Voucher Everyday+
        // voucher book
        "2c92a0fd6205707201621fa1350710e3", //Voucher Saturday+
        "2c92a0fe56fe33ff0157040d4b824168", //Voucher Sunday+
        "2c92a0fd56fe26b60157040cdd323f76", //Voucher Weekend+
        "2c92a0fc56fe26ba0157040c5ea17f6a", //Voucher Sixday+
        "2c92a0ff56fe33f50157040bbdcf3ae4", //Voucher Everyday+
        // Home delivery
        "2c92a0ff6205708e01622484bb2c4613", //Saturday+"
        "2c92a0fd560d13880156136b8e490f8b", //Sunday+"
        "2c92a0ff560d311b0156136b9f5c3968", //Weekend+"
        "2c92a0ff560d311b0156136b697438a9", //Sixday+"
        "2c92a0fd560d132301560e43cf041a3c" //Everyday+"
      )
      possibleProductRatePlanIds.map(productRatePlanId => {
        val attributes = mapper
          .attributesFromSupporterRatePlans(
            identityId,
            List(ratePlanItem(productRatePlanId))
          )

        attributes.PaperSubscriptionExpiryDate must beSome(termEndDate)
        attributes.latestDigitalSubscriptionExpiryDate must beSome(termEndDate)
      })
    }

    "identify memberships correctly" in {
      val possibleProductRatePlanIds = Map(
        "2c92a0fb4ce4b8e7014ce711d3c37e60" -> "Friend",
        "2c92a0f9479fb46d0147d0155c6f558b" -> "Friend",
        "2c92a0f949efde7c0149f1f18162178e" -> "Staff",
        "2c92a0f94c547592014c69f5b0ff4f7e" -> "Supporter",
        "2c92a0fb4c5481db014c69f4a1e03bbd" -> "Supporter",
        "2c92a0fb4bb97034014bbbc562114fef" -> "Supporter",
        "2c92a0fb4bb97034014bbbc562604ff7" -> "Supporter",
        "2c92a0fb4c5481dc014c69f95fce7240" -> "Partner",
        "2c92a0f94c54758b014c69f813bd39ec" -> "Partner",
        "2c92a0f9479fb46d0147d0155ca15595" -> "Partner",
        "2c92a0f9479fb46d0147d0155cb15596" -> "Partner",
        "2c92a0fb4c5481db014c69fb9118704b" -> "Patron",
        "2c92a0f94c547592014c69fb0c4274fc" -> "Patron",
        "2c92a0f9479fb46d0147d0155bf9557a" -> "Patron",
        "2c92a0f9479fb46d0147d0155c245581" -> "Patron"
      )

      possibleProductRatePlanIds.map { case (productRatePlanId, tier) =>
        mapper.attributesFromSupporterRatePlans(
          identityId,
          List(ratePlanItem(productRatePlanId))
        ).Tier must beSome(tier)
      }.toList
    }
  }
}
