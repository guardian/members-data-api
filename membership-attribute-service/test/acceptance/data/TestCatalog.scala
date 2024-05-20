package acceptance.data

import acceptance.data.Randoms.randomId
import acceptance.data.TestCatalogPlan._
import acceptance.data.TestPlans.{testDigipackPlans, testPaidMembershipPlans, testSupporterPlusPlans, weeklyPlans}
import com.gu.memsub.Benefit._
import com.gu.memsub.BillingPeriod._
import com.gu.memsub.Product.Membership
import com.gu.memsub.Subscription.ProductRatePlanId
import com.gu.memsub.subsv2._
import com.gu.memsub._
import scalaz.NonEmptyList

object TestCatalogPlan {
  def randomPlanId(): ProductRatePlanId = ProductRatePlanId(randomId("productRatePlanId"))

  def apply[P <: Product, C <: ChargeList, S <: Status](
      product: P,
      name: String,
      charges: C,
      status: S,
      id: ProductRatePlanId = randomPlanId(),
      description: String = "",
      saving: Option[Int] = None,
  ): CatalogPlan[P, C, S] =
    CatalogPlan[P, C, S](id, product, name, description, saving, charges, status)

  def paid[P <: Product, B <: Benefit, BP <: BillingPeriod](
      product: P,
      benefit: B,
      billingPeriod: BP,
      name: String,
      amount: Double,
  ): CatalogPlan[P, SingleCharge[B, BP], Current] =
    TestCatalogPlan[P, SingleCharge[B, BP], Current](
      product = product,
      name = name + "Paid",
      charges = TestSingleCharge[B, BP](benefit, billingPeriod, TestPricingSummary.gbp(amount)),
      status = Status.current,
    )

  def monthlyPaid[P <: Product, B <: Benefit](
      product: P,
      benefit: B,
      name: String,
      amount: Double,
  ): CatalogPlan[P, SingleCharge[B, Month.type], Current] =
    paid(product, benefit, Month, name + "Monthly", amount)

  def sixWeeksPaid[P <: Product, B <: Benefit](
      product: P,
      benefit: B,
      name: String,
      amount: Double,
  ): CatalogPlan[P, SingleCharge[B, SixWeeks.type], Current] =
    paid(product, benefit, SixWeeks, name + "SixWeeks", amount)

  def quarterlyPaid[P <: Product, B <: Benefit](
      product: P,
      benefit: B,
      name: String,
      amount: Double,
  ): CatalogPlan[P, SingleCharge[B, Quarter.type], Current] =
    paid(product, benefit, Quarter, name + "Quarterly", amount)

  def yearlyPaid[P <: Product, B <: Benefit](
      product: P,
      benefit: B,
      name: String,
      amount: Double,
  ): CatalogPlan[P, SingleCharge[B, Year.type], Current] =
    paid(product, benefit, Year, name + "Yearly", amount)

  def threeMonthsPaid[P <: Product, B <: Benefit](
      product: P,
      benefit: B,
      name: String,
      amount: Double,
  ): CatalogPlan[P, SingleCharge[B, ThreeMonths.type], Current] =
    paid(product, benefit, ThreeMonths, name + "ThreeMonths", amount)

  def oneYearPaid[P <: Product, B <: Benefit](
      product: P,
      benefit: B,
      name: String,
      amount: Double,
  ): CatalogPlan[P, SingleCharge[B, OneYear.type], Current] =
    paid(product, benefit, OneYear, name + "OneYear", amount)

  def paperCharges[P <: Product, BP <: BillingPeriod](product: P, name: String): CatalogPlan[P, PaperCharges, Current] =
    TestCatalogPlan[P, PaperCharges, Current](
      product = product,
      name = name + "PaperCharges",
      charges = TestPaperCharges(),
      status = Status.current,
    )
}

object TestPaperCharges {
  def apply(
      dayPrices: Map[PaperDay, PricingSummary] = DefaultChargesMap,
      digipack: Option[PricingSummary] = Some(TestPricingSummary.gbp(10)),
  ): PaperCharges = {
    PaperCharges(dayPrices, digipack)
  }

  val DefaultChargesMap = Map(
    MondayPaper -> TestPricingSummary.gbp(1),
    TuesdayPaper -> TestPricingSummary.gbp(1),
    WednesdayPaper -> TestPricingSummary.gbp(1),
    ThursdayPaper -> TestPricingSummary.gbp(1),
    FridayPaper -> TestPricingSummary.gbp(1),
    SaturdayPaper -> TestPricingSummary.gbp(2),
    SundayPaper -> TestPricingSummary.gbp(2),
  )
}

object TestPlans {
  def testPaidMembershipPlans[B <: Benefit](benefit: B): MembershipPlans[B] = {
    MembershipPlans(
      monthlyPaid(Membership, benefit, "Membership", 10),
      yearlyPaid(Membership, benefit, "Membership", 120),
    )
  }

  def testDigipackPlans(): DigipackPlans = {
    DigipackPlans(
      monthlyPaid(Product.Digipack, Benefit.Digipack, "Digipack", 5),
      quarterlyPaid(Product.Digipack, Benefit.Digipack, "Digipack", 15),
      yearlyPaid(Product.Digipack, Benefit.Digipack, "Digipack", 60),
    )
  }

  def testSupporterPlusPlans(): SupporterPlusPlans = {
    SupporterPlusPlans(
      TestCatalogPlan(
        product = Product.SupporterPlus,
        name = "SupporterPlus",
        charges = SupporterPlusCharges(Month, List(TestPricingSummary.gbp(10), TestPricingSummary.gbp(0))),
        status = Status.current,
      ),
      TestCatalogPlan(
        product = Product.SupporterPlus,
        name = "SupporterPlus",
        charges = SupporterPlusCharges(Year, List(TestPricingSummary.gbp(95), TestPricingSummary.gbp(0))),
        status = Status.current,
      ),
    )
  }

  def weeklyPlans(): WeeklyPlans =
    WeeklyPlans(
      WeeklyZoneAPlans(
        sixWeeksPaid(Product.WeeklyZoneA, Benefit.Weekly, "WeeklyZoneA", 4),
        quarterlyPaid(Product.WeeklyZoneA, Benefit.Weekly, "WeeklyZoneA", 8),
        yearlyPaid(Product.WeeklyZoneA, Benefit.Weekly, "WeeklyZoneA", 32),
        oneYearPaid(Product.WeeklyZoneA, Benefit.Weekly, "WeeklyZoneA", 32),
      ),
      WeeklyZoneBPlans(
        quarterlyPaid(Product.WeeklyZoneB, Benefit.Weekly, "WeeklyZoneB", 8),
        yearlyPaid(Product.WeeklyZoneB, Benefit.Weekly, "WeeklyZoneB", 32),
        oneYearPaid(Product.WeeklyZoneB, Benefit.Weekly, "WeeklyZoneB", 32),
      ),
      WeeklyZoneCPlans(
        sixWeeksPaid(Product.WeeklyZoneC, Benefit.Weekly, "WeeklyZoneC", 4),
        quarterlyPaid(Product.WeeklyZoneC, Benefit.Weekly, "WeeklyZoneC", 8),
        yearlyPaid(Product.WeeklyZoneC, Benefit.Weekly, "WeeklyZoneC", 32),
      ),
      WeeklyDomesticPlans(
        sixWeeksPaid(Product.WeeklyDomestic, Benefit.Weekly, "WeeklyDomestic", 4),
        quarterlyPaid(Product.WeeklyDomestic, Benefit.Weekly, "WeeklyDomestic", 8),
        yearlyPaid(Product.WeeklyDomestic, Benefit.Weekly, "WeeklyDomestic", 32),
        monthlyPaid(Product.WeeklyDomestic, Benefit.Weekly, "WeeklyDomestic", 3),
        oneYearPaid(Product.WeeklyDomestic, Benefit.Weekly, "WeeklyDomestic", 36),
        threeMonthsPaid(Product.WeeklyDomestic, Benefit.Weekly, "WeeklyDomestic", 9),
      ),
      WeeklyRestOfWorldPlans(
        sixWeeksPaid(Product.WeeklyRestOfWorld, Benefit.Weekly, "WeeklyRestOfWorld", 4),
        quarterlyPaid(Product.WeeklyRestOfWorld, Benefit.Weekly, "WeeklyRestOfWorld", 8),
        yearlyPaid(Product.WeeklyRestOfWorld, Benefit.Weekly, "WeeklyRestOfWorld", 32),
        monthlyPaid(Product.WeeklyRestOfWorld, Benefit.Weekly, "WeeklyRestOfWorld", 3),
        oneYearPaid(Product.WeeklyRestOfWorld, Benefit.Weekly, "WeeklyRestOfWorld", 36),
        threeMonthsPaid(Product.WeeklyRestOfWorld, Benefit.Weekly, "WeeklyRestOfWorld", 9),
      ),
    )
}

object TestCatalog {
  def apply(
      supporter: MembershipPlans[Supporter.type] = testPaidMembershipPlans(Supporter),
      partner: MembershipPlans[Partner.type] = testPaidMembershipPlans(Partner),
      patron: MembershipPlans[Patron.type] = testPaidMembershipPlans(Patron),
      digipack: DigipackPlans = testDigipackPlans(),
      supporterPlus: SupporterPlusPlans = testSupporterPlusPlans(),
      contributor: CatalogPlan.Contributor = monthlyPaid(Product.Contribution, Benefit.Contributor, "Contributor", 12),
      voucher: NonEmptyList[CatalogPlan.Voucher] = NonEmptyList(paperCharges(Product.Voucher, "Voucher")),
      digitalVoucher: NonEmptyList[CatalogPlan.DigitalVoucher] = NonEmptyList(paperCharges(Product.DigitalVoucher, "DigitalVoucher")),
      delivery: NonEmptyList[CatalogPlan.Delivery] = NonEmptyList(paperCharges(Product.Delivery, "Delivery")),
      weekly: WeeklyPlans = weeklyPlans(),
      map: Map[ProductRatePlanId, CatalogZuoraPlan] = Map.empty,
  ): Catalog = Catalog(
    supporter,
    partner,
    patron,
    digipack,
    supporterPlus,
    contributor,
    voucher,
    digitalVoucher,
    delivery,
    weekly,
    map,
  )
}
