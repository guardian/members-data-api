package services

import models.{Attributes, SupporterRatePlanItem}
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import services.SupporterRatePlanToAttributesMapper.productRatePlanMappings
import services.SupporterRatePlanToAttributesMapperTest.allActiveProductRatePlans

class SupporterRatePlanToAttributesMapperTest extends Specification {
  val mapper = new SupporterRatePlanToAttributesMapper("PROD")
  val identityId = "999"
  val termEndDate = LocalDate.now().plusDays(5)

  def ratePlanItem(ratePlanId: String, termEndDate: LocalDate = termEndDate) = SupporterRatePlanItem(
    identityId,
    "some-rate-plan-id",
    ratePlanId,
    termEndDate
  )

  "SupporterRatePlanToAttributesMapper" should {
    "identify a monthly contribution" in {
      mapper.attributesFromSupporterRatePlans(
        identityId,
        List(ratePlanItem("2c92a0fc5aacfadd015ad24db4ff5e97"))
      )  should beSome.which(_.RecurringContributionPaymentPlan must beSome("Monthly Contribution"))
    }

    "identify an annual contribution" in {
      mapper.attributesFromSupporterRatePlans(
        identityId,
        List(ratePlanItem("2c92a0fc5e1dc084015e37f58c200eea"))
      )  should beSome.which(_.RecurringContributionPaymentPlan must beSome("Annual Contribution"))
    }

    "identify a Digital Subscription" in {
      val possibleProductRatePlanIds = List(
        "2c92a0fb4edd70c8014edeaa4eae220a", // Monthly
        "2c92a0fb4edd70c8014edeaa4e972204", // Annual
        "2c92a00d71c96bac0171df3a5622740f", // Corporate
        "2c92a00d779932ef0177a65430d30ac1", // Three Month Gift
        "2c92a00c77992ba70177a6596f710265", // One Year Gift
        "2c92a0ff73add07f0173b99f14390afc", // Deprecated Three Month Gift
        "2c92a00773adc09d0173b99e4ded7f45" // Deprecated One Year Gift
      )
      possibleProductRatePlanIds.map(productRatePlanId =>
        mapper
          .attributesFromSupporterRatePlans(
            identityId,
            List(ratePlanItem(productRatePlanId))
          ) should beSome.which(_.latestDigitalSubscriptionExpiryDate must beSome(termEndDate))
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
          ) should beSome.which(_.GuardianWeeklySubscriptionExpiryDate must beSome(termEndDate))
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
          ) should beSome.which(_.PaperSubscriptionExpiryDate must beSome(termEndDate))
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
        val maybeAttributes = mapper
          .attributesFromSupporterRatePlans(
            identityId,
            List(ratePlanItem(productRatePlanId))
          )

        maybeAttributes should beSome.which { attributes =>
          attributes.PaperSubscriptionExpiryDate should beSome(termEndDate)
          attributes.latestDigitalSubscriptionExpiryDate must beSome(termEndDate)
        }
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
        mapper
          .attributesFromSupporterRatePlans(
            identityId,
            List(ratePlanItem(productRatePlanId))
          ) should beSome.which(_.Tier must beSome(tier))
      }.toList
    }

    "handle an empty list of supporterProductRatePlanIds correctly" in {
        mapper
          .attributesFromSupporterRatePlans(
            identityId,
            Nil
          ) should beNone
    }

    "handle supporter with multiple products correctly" in {
      mapper
        .attributesFromSupporterRatePlans(
          identityId,
          List(
            ratePlanItem("2c92a0f94c547592014c69f5b0ff4f7e"),
            ratePlanItem("2c92a0fc5aacfadd015ad24db4ff5e97"),
            ratePlanItem("2c92a0fb4edd70c8014edeaa4eae220a"),
            ratePlanItem("2c92a00870ec598001710740d0d83017"),
            ratePlanItem("2c92a0fe6619b4b601661ab300222651")
          )
        ) should beSome(
          Attributes(identityId, Some("Supporter"),Some("Monthly Contribution"), None, None, Some(termEndDate), Some(termEndDate), Some(termEndDate), None, None)
      )
    }

    "always return the latest term end date if there are multiples" in {
      val furthestEndDate = LocalDate.now().plusYears(1)
      mapper
        .attributesFromSupporterRatePlans(
          identityId,
          List(
            ratePlanItem("2c92a0ff56fe33f50157040bbdcf3ae4", furthestEndDate), // Everyday+
            ratePlanItem("2c92a0fb4edd70c8014edeaa4eae220a"), //Digital pack
            ratePlanItem("2c92a00870ec598001710740d0d83017"), //Sunday
          )
        ) should beSome(
        Attributes(
          UserId = identityId,
          Tier = None,
          RecurringContributionPaymentPlan = None,
          OneOffContributionDate = None,
          MembershipJoinDate = None,
          DigitalSubscriptionExpiryDate = Some(furthestEndDate),
          PaperSubscriptionExpiryDate = Some(furthestEndDate),
          GuardianWeeklySubscriptionExpiryDate = None,
          LiveAppSubscriptionExpiryDate = None,
          AlertAvailableFor = None
        )
      )
    }

    "have a product rate plan for all active subscriptions" in {

      val allMappedProductRatePlans: List[String] = productRatePlanMappings("PROD").keys.flatten.toList

      val allActiveProductRatePlanIds = allActiveProductRatePlans.map(_._1)

      allActiveProductRatePlans.map { case (productRatePlanId, _) =>
        allMappedProductRatePlans should contain(productRatePlanId)
      }

    }

    "find unused rate plans" in {
      val allMappedProductRatePlans: List[String] = productRatePlanMappings("PROD").keys.flatten.toList

      val allActiveProductRatePlanIds = allActiveProductRatePlans.map(_._1)
      val allUnused = allMappedProductRatePlans.filter(productRatePlanId => !allActiveProductRatePlanIds.contains(productRatePlanId))
      System.out.println(s"There are ${allUnused.length} mapped rate plans which appear to be unused") //TODO: Should we remove legacy product rate plan ids from the mapper
      success
    }
  }
}

object SupporterRatePlanToAttributesMapperTest {
  // This is a list of all active product rate plans from an extract taken
  // using the support-frontend/supporter-product-data project on 26th Feb 2021
  val allActiveProductRatePlans = List(
    ("2c92a0fc5e1dc084015e37f58c200eea", "Annual Contribution"),
    ("2c92a0fd56fe270b0157040dd79b35da", "Everyday"),
    ("2c92a0fc5aacfadd015ad24db4ff5e97", "Monthly Contribution"),
    ("2c92a0fb4edd70c8014edeaa4eae220a", "Digital Pack Monthly"),
    ("2c92a0fb4edd70c8014edeaa4e972204", "Digital Pack Annual"),
    ("2c92a0fe6619b4b301661aa494392ee2", "GW Oct 18 - Quarterly - Domestic"),
    ("2c92a0fe6619b4b901661aa8e66c1692", "GW Oct 18 - Annual - Domestic"),
    ("2c92a0fb4c5481db014c69f4a1e03bbd", "Non Founder Supporter - annual"),
    ("2c92a0ff56fe33f00157040f9a537f4b", "Weekend"),
    ("2c92a0ff56fe33f50157040bbdcf3ae4", "Everyday+"),
    ("2c92a0fe5af9a6b9015b0fe1ecc0116c", "Sunday"),
    ("2c92a0ff67cebd0d0167f0a1a834234e", "GW Oct 18 - 1 Year - Domestic"),
    ("2c92a0fd56fe270b0157040e42e536ef", "Sixday"),
    ("2c92a0fb4ce4b8e7014ce711d3c37e60", "Friends"),
    ("2c92a0fd6205707201621fa1350710e3", "Saturday+"),
    ("2c92a0fe56fe33ff0157040d4b824168", "Sunday+"),
    ("2c92a0fd56fe26b60157040cdd323f76", "Weekend+"),
    ("2c92a0f94c547592014c69f5b0ff4f7e", "Non Founder Supporter - monthly"),
    ("2c92a0086619bf8901661ab02752722f", "GW Oct 18 - Quarterly - ROW"),
    ("2c92a0fe6619b4b601661ab300222651", "GW Oct 18 - Annual - ROW"),
    ("2c92a0fc56fe26ba0157040c5ea17f6a", "Sixday+"),
    ("2c92a0fd5614305c01561dc88f3275be", "Weekend"),
    ("2c92a0fd560d13880156136b72e50f0c", "Everyday"),
    ("2c92a0ff5af9b657015b0fea5b653f81", "Sunday"),
    ("2c92a00870ec598001710740ca532f69", "Sixday"),
    ("2c92a00870ec598001710740d0d83017", "Sunday"),
    ("2c92a00870ec598001710740d24b3022", "Weekend"),
    ("2c92a00870ec598001710740c78d2f13", "Everyday"),
    ("2c92a0ff560d311b0156136f2afe5315", "Sixday"),
    ("2c92a00e6dd988e2016df85387417498", "GW Oct 18 - 3 Month - Domestic"),
    ("2c92a0ff73add07f0173b99f14390afc", "Digital Subscription Three Month Fixed - Deprecated"),
    ("2c92a00773adc09d0173b99e4ded7f45", "Digital Subscription One Year Fixed - Deprecated"),
    ("2c92a0ff67cebd140167f0a2f66a12eb", "GW Oct 18 - 1 Year - ROW"),
    ("2c92a00c77992ba70177a6596f710265", "Digital Subscription One Year Fixed - One Time Charge"),
    ("2c92a0076dd9892e016df8503e7c6c48", "GW Oct 18 - 3 Month - ROW"),
    ("2c92a0fd6205707201621f9f6d7e0116", "Saturday"),
    ("2c92a0fd5e1dcf0d015e3cb39d0a7ddb", "Saturday"),
    ("2c92a0ff560d311b0156136b9f5c3968", "Weekend+"),
    ("2c92a00d779932ef0177a65430d30ac1", "Digital Subscription Three Month Fixed - One Time Charge"),
    ("2c92a0f94c54758b014c69f813bd39ec", "Non Founder Partner - annual"),
    ("2c92a0fd560d13880156136b8e490f8b", "Sunday+"),
    ("2c92a0fb4c5481dc014c69f95fce7240", "Non Founder Partner - monthly")
  )
}
