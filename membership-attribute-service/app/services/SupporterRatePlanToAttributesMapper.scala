package services

import models.{Attributes, DynamoSupporterRatePlanItem}
import org.joda.time.LocalDate
import services.MembershipTier.{Friend, Partner, Patron, Staff, Supporter, getMostValuableTier}
import services.SupporterRatePlanToAttributesMapper.productRatePlanMappings

class SupporterRatePlanToAttributesMapper(stage: String) {

  def attributesFromSupporterRatePlans(identityId: String, supporterRatePlanItems: List[DynamoSupporterRatePlanItem]) = {
    supporterRatePlanItems
      .filter(_.termEndDate.isAfter(LocalDate.now.minusDays(1)))
      .sortWith((first, second) => first.termEndDate.isBefore(second.termEndDate))
      .foldLeft[Option[Attributes]](None) { (maybeAttributes, item) =>
        mapRatePlanToAttributes(maybeAttributes, item, identityId)
      }
  }

  private def mapRatePlanToAttributes(maybeAttributes: Option[Attributes], ratePlanItem: DynamoSupporterRatePlanItem, identityId: String) =
    productRatePlanMappings(stage)
      .collectFirst {
        case (ids, transformer) if ids.contains(ratePlanItem.productRatePlanId) =>
          Some(transformer.transform(maybeAttributes.getOrElse(Attributes(identityId)), ratePlanItem))
      }
      .getOrElse(maybeAttributes)

}

object SupporterRatePlanToAttributesMapper {

  type Stage = String
  type ProductRatePlanId = String
  val digitalSubTransformer: AttributeTransformer = (attributes: Attributes, supporterRatePlanItem: DynamoSupporterRatePlanItem) =>
    attributes.copy(
      DigitalSubscriptionExpiryDate = Some(supporterRatePlanItem.termEndDate),
    )
  val monthlyContributionTransformer: AttributeTransformer = (attributes: Attributes, _: DynamoSupporterRatePlanItem) =>
    attributes.copy(RecurringContributionPaymentPlan = Some("Monthly Contribution"))
  val annualContributionTransformer: AttributeTransformer = (attributes: Attributes, _: DynamoSupporterRatePlanItem) =>
    attributes.copy(RecurringContributionPaymentPlan = Some("Annual Contribution"))
  val paperTransformer: AttributeTransformer = (attributes: Attributes, supporterRatePlanItem: DynamoSupporterRatePlanItem) =>
    attributes.copy(
      PaperSubscriptionExpiryDate = Some(supporterRatePlanItem.termEndDate),
    )
  val paperPlusDigitalTransformer: AttributeTransformer = (attributes: Attributes, supporterRatePlanItem: DynamoSupporterRatePlanItem) =>
    attributes.copy(
      PaperSubscriptionExpiryDate = Some(supporterRatePlanItem.termEndDate),
      DigitalSubscriptionExpiryDate = Some(supporterRatePlanItem.termEndDate),
    )
  val guardianWeeklyTransformer: AttributeTransformer = (attributes: Attributes, supporterRatePlanItem: DynamoSupporterRatePlanItem) =>
    attributes.copy(
      GuardianWeeklySubscriptionExpiryDate = Some(supporterRatePlanItem.termEndDate),
    )
  val guardianPatronTransformer: AttributeTransformer = (attributes: Attributes, supporterRatePlanItem: DynamoSupporterRatePlanItem) =>
    attributes.copy(
      GuardianPatronExpiryDate = Some(supporterRatePlanItem.termEndDate),
    )
  val guardianPatronProductRatePlanId = "guardian_patron"
  val productRatePlanMappings: Map[Stage, Map[List[ProductRatePlanId], AttributeTransformer]] =
    Map(
      "PROD" -> Map(
        List(guardianPatronProductRatePlanId) -> guardianPatronTransformer,
        List(
          "2c92a0fb4edd70c8014edeaa4eae220a",
          "2c92a0fb4edd70c8014edeaa4e972204",
          "2c92a00d71c96bac0171df3a5622740f",
          "2c92a00d779932ef0177a65430d30ac1",
          "2c92a00c77992ba70177a6596f710265",
          "2c92a0ff73add07f0173b99f14390afc",
          "2c92a00773adc09d0173b99e4ded7f45",
          "2c92a0fb4edd70c8014edeaa4e8521fe",
        ) -> digitalSubTransformer,
        List("2c92a0fc5aacfadd015ad24db4ff5e97") -> monthlyContributionTransformer,
        List("2c92a0fc5e1dc084015e37f58c200eea") -> annualContributionTransformer,
        List(
          "2c92a00870ec598001710740cdd02fbd",
          "2c92a00870ec598001710740d0d83017",
          "2c92a00870ec598001710740d24b3022",
          "2c92a00870ec598001710740ca532f69",
          "2c92a00870ec598001710740c78d2f13",
          "2c92a0fd6205707201621f9f6d7e0116",
          "2c92a0fe5af9a6b9015b0fe1ecc0116c",
          "2c92a0ff56fe33f00157040f9a537f4b",
          "2c92a0fd56fe270b0157040e42e536ef",
          "2c92a0fd56fe270b0157040dd79b35da",
          "2c92a0fd5e1dcf0d015e3cb39d0a7ddb",
          "2c92a0ff5af9b657015b0fea5b653f81",
          "2c92a0fd5614305c01561dc88f3275be",
          "2c92a0ff560d311b0156136f2afe5315",
          "2c92a0fd560d13880156136b72e50f0c",
          "2c92a0ff56fe33f001572334561765c1",
          "2c92a0fd596d321a0159735a7b150e43",
        ) -> paperTransformer,
        List(
          "2c92a00870ec598001710740ce702ff0",
          "2c92a00870ec598001710740cf9e3004",
          "2c92a00870ec598001710740c6672ee7",
          "2c92a00870ec598001710740c4582ead",
          "2c92a00870ec598001710740d3d03035",
          "2c92a0fd6205707201621fa1350710e3",
          "2c92a0fe56fe33ff0157040d4b824168",
          "2c92a0fd56fe26b60157040cdd323f76",
          "2c92a0fc56fe26ba0157040c5ea17f6a",
          "2c92a0ff56fe33f50157040bbdcf3ae4",
          "2c92a0ff6205708e01622484bb2c4613",
          "2c92a0fd560d13880156136b8e490f8b",
          "2c92a0ff560d311b0156136b9f5c3968",
          "2c92a0ff560d311b0156136b697438a9",
          "2c92a0fd560d132301560e43cf041a3c",
        ) -> paperPlusDigitalTransformer,
        List(
          "2c92a0fe6619b4b601661ab300222651",
          "2c92a0ff67cebd140167f0a2f66a12eb",
          "2c92a0086619bf8901661ab02752722f",
          "2c92a0076dd9892e016df8503e7c6c48",
          "2c92a0fe6619b4b901661aa8e66c1692",
          "2c92a0ff67cebd0d0167f0a1a834234e",
          "2c92a0fe6619b4b301661aa494392ee2",
          "2c92a00e6dd988e2016df85387417498",
          "2c92a0fd58cf57000158f30ae6d06f2a",
          "2c92a0ff58bdf4eb0158f2ecc89c1034",
          "2c92a0ff58bdf4ee0158f30905e82181",
          "2c92a0fd5a5adc8b015a5c690d0d1ec6",
          "2c92a0ff5a4b85e7015a4cf95d352a07",
          "2c92a0ff5a5adca9015a611f77db4431",
          "2c92a0fc5a2a49f0015a41f473da233a",
          "2c92a0fe5a5ad344015a5c67b1144250",
          "2c92a0ff59d9d540015a41a40b3e07d3",
          "2c92a0fd5a5adc8b015a5c65074b7c41",
          "2c92a0ff5a5adca7015a5c4af5963efa",
          "2c92a0fe5a5ad349015a5c61d6e05d8d",
          "2c92a0fe57d0a0c40157d74240de5543",
          "2c92a0ff57d0a0b60157d741e722439a",
          "2c92a0ff58bdf4eb0158f307eccf02af",
          "2c92a0fc6ae918b6016b080950e96d75",
          "2c92a0fc5b42d2c9015b6259f7f40040",
          "2c92a0fd57d0a9870157d7412f19424f",
          "2c92a0fe57d0a0c40157d74241005544",
          "2c92a0ff58bdf4eb0158f307ed0e02be",
        ) -> guardianWeeklyTransformer,
        List(
          "2c92a0fb4ce4b8e7014ce711d3c37e60",
          "2c92a0f9479fb46d0147d0155c6f558b",
        ) -> memberTransformer(Friend),
        List(
          "2c92a0f949efde7c0149f1f18162178e",
        ) -> memberTransformer(Staff),
        List(
          "2c92a0f94c547592014c69f5b0ff4f7e",
          "2c92a0fb4c5481db014c69f4a1e03bbd",
          "2c92a0fb4bb97034014bbbc562114fef",
          "2c92a0fb4bb97034014bbbc562604ff7",
        ) -> memberTransformer(Supporter),
        List(
          "2c92a0fb4c5481dc014c69f95fce7240",
          "2c92a0f94c54758b014c69f813bd39ec",
          "2c92a0f9479fb46d0147d0155ca15595",
          "2c92a0f9479fb46d0147d0155cb15596",
        ) -> memberTransformer(Partner),
        List(
          "2c92a0fb4c5481db014c69fb9118704b",
          "2c92a0f94c547592014c69fb0c4274fc",
          "2c92a0f9479fb46d0147d0155bf9557a",
          "2c92a0f9479fb46d0147d0155c245581",
        ) -> memberTransformer(Patron),
      ),
      "UAT" -> Map(
        List("guardian_patron") -> guardianPatronTransformer,
        List(
          "2c92c0f94f2acf73014f2c908f671591",
          "2c92c0f84f2ac59d014f2c94aea9199e",
          "2c92c0f971c65df50171dfabef87093d",
          "2c92c0f9778c090d017795ef3000352f",
          "2c92c0f9778c0900017795da493b4f85",
        ) -> digitalSubTransformer,
        List("2c92c0f85ab269be015acd9d014549b7") -> monthlyContributionTransformer,
        List("2c92c0f95e1d5c9c015e38f8c87d19a1") -> annualContributionTransformer,
        List(
          "2c92c0f870f682820171070488df42ce",
          "2c92c0f870f682820171070487f142c4",
          "2c92c0f870f682820171070477d841e2",
          "2c92c0f870f68282017107047d054230",
          "2c92c0f870f682820171070474ee419d",
          "2c92c0f961f9cf300161fc02a7d805c9",
          "2c92c0f95aff3b54015b0ee0eb500b2e",
          "2c92c0f855c9f4b20155d9f1db9b5199",
          "2c92c0f955ca02910155da254a641fb3",
          "2c92c0f855c9f4b20155d9f1d3d4512a",
          "2c92c0f85b8fa30e015b9108a83253c7",
          "2c92c0f95aff3b54015b0ede33bc04f2",
          "2c92c0f955ca02900155da27f83c2d9b",
          "2c92c0f955ca02900155da27ff142e01",
          "2c92c0f955ca02900155da27f55b2d5f",
        ) -> paperTransformer,
        List(
          "2c92c0f870f682820171070489d542da",
          "2c92c0f870f68282017107047b214214",
          "2c92c0f870f682820171070478d441f5",
          "2c92c0f870f682820171070470ad4120",
          "2c92c0f870f682820171070481bf4264",
          "2c92c0f961f9cf350161fc0454283f3e",
          "2c92c0f858aa38af0158b9dae19110a3",
          "2c92c0f855c9f4b20155d9f1dd0651ab",
          "2c92c0f855c9f4540155da2607db6402",
          "2c92c0f955ca02920155da240cdb4399",
          "2c92c0f961f9cf300161fbfa943b6f54",
          "2c92c0f955ca02900155da27f4872d4d",
          "2c92c0f955ca02900155da27f9402dad",
          "2c92c0f955ca02900155da27f29e2d13",
          "2c92c0f955ca02900155da2803b02e33",
        ) -> paperPlusDigitalTransformer,
        List(
          "2c92c0f9660fc4d70166109a2eb0607c",
          "2c92c0f967caee360167f044cd0d4adc",
          "2c92c0f9660fc4d70166109c01465f10",
          "2c92c0f96df75b5a016df84084fb356d",
          "2c92c0f9660fc4d70166107fa5412641",
          "2c92c0f867cae0700167f043870d6d0e",
          "2c92c0f8660fb5d601661081ea010391",
          "2c92c0f96df75b51016df8444f36362f",
        ) -> guardianWeeklyTransformer,
        List(
          "2c92c0f94cc6ea05014cdb4b1d1f037d",
          "2c92c0f848f362750148f4c2727379d7",
        ) -> memberTransformer(Friend),
        List(
          "2c92c0f849f118740149f1d61ad07723",
        ) -> memberTransformer(Staff),
        List(
          "2c92c0f84c5100b6014c569ad3a23d10",
          "2c92c0f84c5100b6014c569b83b33ebd",
          "2c92c0f84bbfeca5014bc0c5a9a12427",
          "2c92c0f84bbfeca5014bc0c5a83f241f",
        ) -> memberTransformer(Supporter),
        List(
          "2c92c0f84c510073014c56948fbe6894",
          "2c92c0f84c510081014c569327003593",
          "2c92c0f848f362750148f4c2729379db",
          "2c92c0f848f362750148f4c2728379d9",
        ) -> memberTransformer(Partner),
        List(
          "2c92c0f94c510a0d014c569070792fa7",
          "2c92c0f84c510081014c568daa112d2a",
          "2c92c0f848f362750148f4c2726079d5",
          "2c92c0f848f362750148f4c2724679d3",
        ) -> memberTransformer(Patron),
      ),
      "DEV" -> Map(
        List("guardian_patron") -> guardianPatronTransformer,
        List(
          "2c92c0f84bbfec8b014bc655f4852d9d",
          "2c92c0f94bbffaaa014bc6a4212e205b",
          "2c92c0f971c65dfe0171c6c1f86e603c",
          "2c92c0f8778bf8f60177915b477714aa",
          "2c92c0f8778bf8cd0177a610cdf230ae",
        ) -> digitalSubTransformer,
        List("2c92c0f85a6b134e015a7fcd9f0c7855") -> monthlyContributionTransformer,
        List("2c92c0f85e2d19af015e3896e824092c") -> annualContributionTransformer,
        List(
          "2c92c0f86fa49142016fa49ea442291b",
          "2c92c0f86fa49142016fa49eb0a42a01",
          "2c92c0f86fa49142016fa49ea0d028b6",
          "2c92c0f86fa49142016fa49e9b9a286f",
          "2c92c0f86fa49142016fa49ea56a2938",
          "2c92c0f861f9c26d0161fc434bfe004c",
          "2c92c0f95aff3b56015b1045fb9332d2",
          "2c92c0f8555ce5cf01556e7f01b81b94",
          "2c92c0f8555ce5cf01556e7f01771b8a",
          "2c92c0f9555cf10501556e84a70440e2",
          "2c92c0f961f9cf300161fc4d2e3e3664",
          "2c92c0f85aff3453015b1041dfd2317f",
          "2c92c0f955c3cf0f0155c5d9df433bf7",
          "2c92c0f955c3cf0f0155c5d9ddf13bc5",
          "2c92c0f955c3cf0f0155c5d9e2493c43",
        ) -> paperTransformer,
        List(
          "2c92c0f86fa49142016fa49eb1732a39",
          "2c92c0f86fa49142016fa49ea90e2976",
          "2c92c0f86fa49142016fa49eaecb29dd",
          "2c92c0f86fa49142016fa49ea1af28c8",
          "2c92c0f86fa49142016fa49eaa492988",
          "2c92c0f961f9cf300161fc44f2661258",
          "2c92c0f955a0b5bf0155b62623846fc8",
          "2c92c0f95aff3b54015b1047efaa2ac3",
          "2c92c0f855c3b8190155c585a95e6f5a",
          "2c92c0f95aff3b53015b10469bbf5f5f",
          "2c92c0f961f9cf300161fc4f71473a34",
          "2c92c0f955c3cf0f0155c5d9e83a3cb7",
          "2c92c0f95aff3b56015b104aa9a13ea5",
          "2c92c0f85aff33ff015b1042d4ba0a05",
          "2c92c0f85aff3453015b10496b5e3d17",
        ) -> paperPlusDigitalTransformer,
        List(
          "2c92c0f965f2122101660fb33ed24a45",
          "2c92c0f967caee410167eff78e7b5244",
          "2c92c0f965f2122101660fb81b745a06",
          "2c92c0f96df75b5a016df81ba1c62609",
          "2c92c0f965d280590165f16b1b9946c2",
          "2c92c0f867cae0700167eff921734f7b",
          "2c92c0f965dc30640165f150c0956859",
          "2c92c0f96ded216a016df491134d4091",
        ) -> guardianWeeklyTransformer,
        List(
          "2c92c0f94c9ca1c5014c9e5c64ba4260",
          "2c92c0f945fee1c90146057402c7066b",
        ) -> memberTransformer(Friend),
        List(
          "2c92c0f849c6e58a0149c73d6f114be2",
        ) -> memberTransformer(Staff),
        List(
          "2c92c0f94c510a0d014c569ba8eb45f7",
          "2c92c0f94c510a01014c569e2d857cfd",
          "2c92c0f84b079582014b2754c07c0f7d",
          "2c92c0f84b079582014b2754bfd70f6d",
        ) -> memberTransformer(Supporter),
        List(
          "2c92c0f94c510a0d014c569a93194575",
          "2c92c0f84c510081014c569a18b04e84",
          "2c92c0f945fee1c9014605749e450969",
          "2c92c0f8471e22bb01471ffe9596366c",
        ) -> memberTransformer(Partner),
        List(
          "2c92c0f84c5100b6014c56908a63216d",
          "2c92c0f94c510a04014c568d648d097d",
          "2c92c0f845fed48301460578277167c3",
          "2c92c0f9471e145d01471ffd7c304df9",
        ) -> memberTransformer(Patron),
      ),
    )

  def memberTransformer(tier: MembershipTier): AttributeTransformer = (attributes: Attributes, _: DynamoSupporterRatePlanItem) =>
    attributes.copy(Tier = getMostValuableTier(tier, attributes.Tier))

  trait AttributeTransformer {
    def transform(attributes: Attributes, supporterRatePlanItem: DynamoSupporterRatePlanItem): Attributes
  }

}

sealed abstract class MembershipTier(val name: String, val value: Int)

object MembershipTier {
  case object Friend extends MembershipTier("Friend", 1)
  case object Staff extends MembershipTier("Staff", 2)
  case object Supporter extends MembershipTier("Supporter", 3)
  case object Partner extends MembershipTier("Partner", 4)
  case object Patron extends MembershipTier("Patron", 5)

  private def fromString(name: String): Option[MembershipTier] =
    List(Friend, Staff, Supporter, Partner, Patron).find(_.name == name)

  def getMostValuableTier(newTier: MembershipTier, existingTier: Option[String]) =
    if (existingTier.flatMap(fromString).exists(_.value > newTier.value))
      existingTier
    else
      Some(newTier.name)

}
