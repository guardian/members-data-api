package com.gu.memsub.subsv2.services

import com.gu.config.SubsV2ProductIds
import com.gu.lib.DateDSL._
import com.gu.memsub.ProductRatePlanChargeProductType._
import com.gu.memsub.Subscription.{ProductId, ProductRatePlanChargeId, ProductRatePlanId, Id => _}
import com.gu.memsub.subsv2.Fixtures.productIds
import com.gu.memsub.subsv2.{Catalog, ProductRatePlan, ProductType}
import com.gu.memsub.{Product, ProductRatePlanChargeProductType, Subscription => _}

object TestCatalog {

  val digipackAnnualPrpId = ProductRatePlanId("2c92c0f94bbffaaa014bc6a4212e205b")
  val partnerPrpId = ProductRatePlanId("2c92c0f84c510081014c569327003593")
  val supporterPrpId = ProductRatePlanId("2c92c0f84bbfeca5014bc0c5a793241d")
  val contributorPrpId = ProductRatePlanId("asdfasdf")
  val supporterPlusPrpId = ProductRatePlanId("8ad08cbd8586721c01858804e3275376")
  val tierThreePrpId = ProductRatePlanId("prpIDtierThree")
  val digipackPrpId = ProductRatePlanId("2c92c0f94f2acf73014f2c908f671591")
  val gw6for6PrpId = ProductRatePlanId("2c92c0f965f212210165f69b94c92d66")
  val homeDeliveryPrpId = ProductRatePlanId("homedelPRPid")
  val gw = ProductRatePlanId("2c92c0f965dc30640165f150c0956859")
  val now = 27 Sep 2016

  object ProductRatePlanChargeIds {
    val contributorChargeId = ProductRatePlanChargeId("foo")
    val supporterPlusChargeId: ProductRatePlanChargeId = ProductRatePlanChargeId("8ad08cbd8586721c01858804e3715378")
    val sPluscontributionChargeId: ProductRatePlanChargeId = ProductRatePlanChargeId("asdasdasdcon")
    val tierThreeDigitalId = ProductRatePlanChargeId("tierthreedigiPRPCid")
    val tierThreeGWId = ProductRatePlanChargeId("tierthreeGWprpcID")
  }
  import ProductRatePlanChargeIds._

  val idForProduct: Map[Product, ProductId] = productIds.map(_.swap).toMap

  val cat = Map[ProductRatePlanId, ProductRatePlan](
    digipackAnnualPrpId -> ProductRatePlan(
      digipackAnnualPrpId,
      "foo",
      idForProduct(Product.Digipack),
      Map(ProductRatePlanChargeId("2c92c0f94bbffaaa014bc6a4213e205d") -> Digipack),
      Some(ProductType("Digital Pack")),
    ),
    partnerPrpId -> ProductRatePlan(
      partnerPrpId,
      "Partner",
      idForProduct(Product.Membership), // might get the wrong one
      Map(ProductRatePlanChargeId("2c92c0f84c510081014c569327593595") -> Partner),
      Some(ProductType("type")),
    ),
    supporterPrpId -> ProductRatePlan(
      supporterPrpId,
      "Supporter",
      idForProduct(Product.Membership), // might get the wrong one
      Map(ProductRatePlanChargeId("2c92c0f84c5100b6014c569b83c23ebf") -> Supporter),
      Some(ProductType("type")),
    ),
    supporterPlusPrpId -> ProductRatePlan(
      supporterPlusPrpId,
      "Supporter Plus",
      idForProduct(Product.SupporterPlus),
      Map(
        supporterPlusChargeId -> SupporterPlus,
        sPluscontributionChargeId -> Contributor,
      ),
      Some(ProductType("type")),
    ),
    tierThreePrpId -> ProductRatePlan(
      supporterPlusPrpId,
      "Supporter Plus",
      idForProduct(Product.SupporterPlus),
      Map(
        tierThreeDigitalId -> SupporterPlus,
        tierThreeGWId -> Weekly,
      ),
      Some(ProductType("type")),
    ),
    digipackPrpId -> ProductRatePlan(
      digipackPrpId,
      "Digipack",
      idForProduct(Product.Digipack),
      Map(ProductRatePlanChargeId("2c92c0f94f2acf73014f2c91940a166d") -> Digipack),
      Some(ProductType("Digital Pack")),
    ),
    gw6for6PrpId -> ProductRatePlan(
      gw6for6PrpId,
      "GW Oct 18 - Six for Six - Domestic",
      idForProduct(Product.WeeklyDomestic),
      Map(ProductRatePlanChargeId("2c92c0f865f204440165f69f407d66f1") -> Weekly),
      Some(ProductType("type")),
    ),
    gw -> ProductRatePlan(
      gw,
      "GW Oct 18 - Quarterly - Domestic",
      idForProduct(Product.WeeklyDomestic),
      Map(ProductRatePlanChargeId("2c92c0f865d273010165f16ada0a4346") -> Weekly),
      Some(ProductType("type")),
    ),
    homeDeliveryPrpId -> ProductRatePlan(
      homeDeliveryPrpId,
      "Newspaper - Home Delivery",
      idForProduct(Product.Delivery),
      Map(ProductRatePlanChargeId("homedelchargeID") -> MondayPaper),
      Some(ProductType("Newspaper - Home Delivery")),
    ),
    contributorPrpId -> ProductRatePlan(
      contributorPrpId,
      "Contributor",
      idForProduct(Product.Contribution),
      Map(contributorChargeId -> Contributor),
      Some(ProductType("type")),
    ),
    Catalog.guardianPatronProductRatePlanId -> ProductRatePlan(
      Catalog.guardianPatronProductRatePlanId,
      "Guardian Patron",
      SubsV2ProductIds.guardianPatronProductId,
      Map[ProductRatePlanChargeId, ProductRatePlanChargeProductType](
        Catalog.guardianPatronProductRatePlanChargeId -> ProductRatePlanChargeProductType.GuardianPatron,
      ),
      Some(ProductType("Membership")),
    ),
  )

  val catalog = Catalog(cat, productIds)

}
