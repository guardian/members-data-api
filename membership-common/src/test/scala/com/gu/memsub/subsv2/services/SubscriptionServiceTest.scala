package com.gu.memsub.subsv2.services

import com.github.nscala_time.time.Implicits._
import com.github.nscala_time.time.Imports.LocalTime
import com.gu.i18n.Currency.GBP
import com.gu.lib.DateDSL._
import com.gu.memsub
import com.gu.memsub.Benefit._
import com.gu.memsub.Subscription.{Id => _, _}
import com.gu.memsub.subsv2.Fixtures._
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.ChargeListReads._
import com.gu.memsub.subsv2.reads.SubPlanReads._
import com.gu.memsub.{Subscription => _, _}
import com.gu.monitoring.SafeLogger
import com.gu.okhttp.RequestRunners.HttpClient
import com.gu.salesforce.ContactId
import com.gu.zuora.{SoapClient, ZuoraRestConfig}
import com.gu.zuora.rest.SimpleClient
import io.lemonlabs.uri.typesafe.dsl._
import okhttp3._
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import utils.Resource
import scalaz.Id._
import scalaz.{-\/, NonEmptyList, \/, \/-}
import utils.TestLogPrefix.testLogPrefix

/** This test just tests plumbing really but at least it is /possible/ to test plumbing
  */
class SubscriptionServiceTest extends Specification {

  // this is the UAT prpId of friend, which we need for the catalog
  val prpId = ProductRatePlanId("2c92c0f94cc6ea05014cdb4b1d1f037d")
  val partnerPrpId = ProductRatePlanId("2c92c0f84c510081014c569327003593")
  val supporterPrpId = ProductRatePlanId("2c92c0f84bbfeca5014bc0c5a793241d")
  val digipackPrpId = ProductRatePlanId("2c92c0f94f2acf73014f2c908f671591")
  val gw6for6PrpId = ProductRatePlanId("2c92c0f965f212210165f69b94c92d66")
  val gw = ProductRatePlanId("2c92c0f965dc30640165f150c0956859")
  val now = 27 Sep 2016

  val cat = Map[ProductRatePlanId, CatalogZuoraPlan](
    prpId -> CatalogZuoraPlan(
      prpId,
      "foo",
      "",
      productIds.staff,
      None,
      List.empty,
      Map(ProductRatePlanChargeId("2c92c0f84cc6d9e5014cdb4c48b02d83") -> Staff),
      Status.current,
      None,
      Some("Membership"),
    ),
    partnerPrpId -> CatalogZuoraPlan(
      partnerPrpId,
      "Partner",
      "",
      productIds.partner,
      None,
      List.empty,
      Map(ProductRatePlanChargeId("2c92c0f84c510081014c569327593595") -> Partner),
      Status.current,
      None,
      Some("type"),
    ),
    supporterPrpId -> CatalogZuoraPlan(
      supporterPrpId,
      "Supporter",
      "",
      productIds.supporter,
      None,
      List.empty,
      Map(ProductRatePlanChargeId("2c92c0f84c5100b6014c569b83b33ebd") -> Supporter),
      Status.current,
      None,
      Some("type"),
    ),
    digipackPrpId -> CatalogZuoraPlan(
      digipackPrpId,
      "Digipack",
      "",
      productIds.digipack,
      None,
      List.empty,
      Map(ProductRatePlanChargeId("2c92c0f94f2acf73014f2c91940a166d") -> Digipack),
      Status.current,
      None,
      Some("type"),
    ),
    gw6for6PrpId -> CatalogZuoraPlan(
      gw6for6PrpId,
      "GW Oct 18 - Six for Six - Domestic",
      "",
      productIds.weeklyDomestic,
      None,
      List.empty,
      Map(ProductRatePlanChargeId("2c92c0f865f204440165f69f407d66f1") -> Weekly),
      Status.current,
      None,
      Some("type"),
    ),
    gw -> CatalogZuoraPlan(
      gw,
      "GW Oct 18 - Quarterly - Domestic",
      "",
      productIds.weeklyDomestic,
      None,
      List.empty,
      Map(ProductRatePlanChargeId("2c92c0f865d273010165f16ada0a4346") -> Weekly),
      Status.current,
      None,
      Some("type"),
    ),
  )

  def jsonResponse(path: String)(req: Request) =
    new Response.Builder()
      .request(req)
      .body(ResponseBody.create(MediaType.parse("application/json"), Resource.getJson(path).toString))
      .message("test")
      .code(200)
      .protocol(Protocol.HTTP_2)
      .build()

  object soapClient extends SoapClient[Id] {
    override def getAccountIds(contactId: ContactId)(implicit logPrefix: SafeLogger.LogPrefix): scalaz.Id.Id[List[AccountId]] =
      List(
        memsub.Subscription.AccountId("foo"),
        memsub.Subscription.AccountId("bar"),
      )
  }

  val subscriptions: HttpClient[scalaz.Id.Id] = new HttpClient[Id]() {
    override def execute(request: Request)(implicit logPrefix: SafeLogger.LogPrefix): scalaz.Id.Id[Response] =
      request.url().uri().getPath match {
        case "/subscriptions/accounts/foo" => jsonResponse("rest/plans/accounts/Friend.json")(request)
        case "/subscriptions/accounts/bar" => jsonResponse("rest/plans/accounts/Friend.json")(request)
        case "/subscriptions/1234" => jsonResponse("rest/plans/Friend.json")(request)
        case "/subscriptions/A-S00063478" => jsonResponse("rest/plans/Upgraded.json")(request)
        case "/subscriptions/A-lead-time" => jsonResponse("rest/cancellation/GW-6for6-lead-time.json")(request)
        case "/subscriptions/A-segment-6for6" => jsonResponse("rest/cancellation/GW-6for6-segment-6for6.json")(request)
        case "/subscriptions/GW-before-bill-run" => jsonResponse("rest/cancellation/GW-before-bill-run.json")(request)
        case "/subscriptions/GW-stale-chargeThroughDate" => jsonResponse("rest/cancellation/GW-stale-chargeThroughDate.json")(request)
        case _ => new Response.Builder().message("test").code(404).protocol(Protocol.HTTP_1_0).request(request).build()
      }
  }

  val rc = new SimpleClient[Id](ZuoraRestConfig("TESTS", "https://localhost", "foo", "bar"), subscriptions)
  val service = new SubscriptionService[Id](Fixtures.productIds, cat, rc, soapClient)

  "Current Plan" should {

    def contributorPlan(startDate: LocalDate, endDate: LocalDate, lastChangeType: Option[String] = None): SubscriptionPlan.Contributor =
      PaidSubscriptionPlan[Product.Contribution, PaidCharge[Benefit.Contributor.type, BillingPeriod]](
        RatePlanId("idContributor"),
        ProductRatePlanId("prpi"),
        "Contributor",
        "desc",
        "Contributor",
        lastChangeType,
        "Contribution",
        Product.Contribution,
        List.empty,
        PaidCharge(
          Contributor,
          BillingPeriod.Month,
          PricingSummary(Map(GBP -> Price(5.0f, GBP))),
          ProductRatePlanChargeId("foo"),
          SubscriptionRatePlanChargeId("noo"),
        ),
        None,
        startDate,
        endDate,
      )
    def staffPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Staff =
      FreeSubscriptionPlan[Product.Membership, FreeCharge[Benefit.Staff.type]](
        RatePlanId("idFriend"),
        ProductRatePlanId("prpi"),
        "Friend",
        "desc",
        "Friend",
        "Membership",
        Product.Membership,
        FreeCharge(Staff, Set(GBP)),
        startDate,
        endDate,
      )
    def legacyStaffPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Staff =
      FreeSubscriptionPlan[Product.Membership, FreeCharge[Benefit.Staff.type]](
        RatePlanId("idLegacyFriend"),
        ProductRatePlanId("prpi"),
        "LegacyFriend",
        "desc",
        "LegacyFriend",
        "Membership",
        Product.Membership,
        FreeCharge(Staff, Set(GBP)),
        startDate,
        endDate,
      )
    def partnerPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Partner =
      PaidSubscriptionPlan[Product.Membership, PaidCharge[Benefit.Partner.type, BillingPeriod]](
        RatePlanId("idPartner"),
        ProductRatePlanId("prpi"),
        "Partner",
        "desc",
        "Partner",
        None,
        "Membership",
        Product.Membership,
        List.empty,
        PaidCharge(
          Partner,
          BillingPeriod.Year,
          PricingSummary(Map(GBP -> Price(149.0f, GBP))),
          ProductRatePlanChargeId("foo"),
          SubscriptionRatePlanChargeId("noo"),
        ),
        None,
        startDate,
        endDate,
      )
    def supporterPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Supporter =
      PaidSubscriptionPlan[Product.Membership, PaidCharge[Benefit.Supporter.type, BillingPeriod]](
        RatePlanId("idSupporter"),
        ProductRatePlanId("prpi"),
        "Supporter",
        "desc",
        "Supporter",
        None,
        "Membership",
        Product.Membership,
        List.empty,
        PaidCharge(
          Supporter,
          BillingPeriod.Year,
          PricingSummary(Map(GBP -> Price(49.0f, GBP))),
          ProductRatePlanChargeId("bar"),
          SubscriptionRatePlanChargeId("nar"),
        ),
        None,
        startDate,
        endDate,
      )
    def digipackPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.Digipack =
      PaidSubscriptionPlan[Product.ZDigipack, PaidCharge[Benefit.Digipack.type, BillingPeriod]](
        RatePlanId("idDigipack"),
        ProductRatePlanId("prpi"),
        "Digipack",
        "desc",
        "Digital Pack",
        None,
        "Digital Pack",
        Product.Digipack,
        List.empty,
        PaidCharge(
          Digipack,
          BillingPeriod.Year,
          PricingSummary(Map(GBP -> Price(119.90f, GBP))),
          ProductRatePlanChargeId("baz"),
          SubscriptionRatePlanChargeId("naz"),
        ),
        None,
        startDate,
        endDate,
      )

    def switchedSupporterPlusPlan(startDate: LocalDate, endDate: LocalDate): SubscriptionPlan.SupporterPlus =
      PaidSubscriptionPlan[Product.SupporterPlus, PaidCharge[Benefit.SupporterPlus.type, BillingPeriod]](
        id = RatePlanId("idSupporterPlus"),
        productRatePlanId = ProductRatePlanId("prpi"),
        name = "SupporterPlus",
        description = "desc",
        productName = "Supporter Plus",
        lastChangeType = Some("Add"),
        productType = "Supporter Plus",
        product = Product.SupporterPlus,
        features = List.empty,
        charges = PaidCharge(
          SupporterPlus,
          BillingPeriod.Year,
          PricingSummary(Map(GBP -> Price(119.90f, GBP))),
          ProductRatePlanChargeId("baz"),
          SubscriptionRatePlanChargeId("naz"),
        ),
        chargedThrough = None,
        start = startDate,
        end = endDate,
      )

    def toSubscription[P <: SubscriptionPlan.AnyPlan](isCancelled: Boolean)(plans: NonEmptyList[P]): Subscription[P] = {
      import com.gu.memsub.Subscription._
      Subscription(
        id = Id(plans.head.id.get),
        name = Name("AS-123123"),
        accountId = AccountId("accountId"),
        startDate = plans.head.start,
        acceptanceDate = plans.head.start,
        termStartDate = plans.head.start,
        termEndDate = plans.head.start + 1.year,
        casActivationDate = None,
        promoCode = None,
        isCancelled = isCancelled,
        hasPendingFreePlan = false,
        plans = CovariantNonEmptyList(plans.head, plans.tail.toList),
        readerType = ReaderType.Direct,
        gifteeIdentityId = None,
        autoRenew = true,
      )
    }

    val referenceDate = 26 Oct 2016

    "tell you that you aren't a contributor immediately after you have switched to supporter plus" in {
      val plans = NonEmptyList(
        contributorPlan(referenceDate, referenceDate + 1.year, Some("Remove")),
        switchedSupporterPlusPlan(referenceDate, referenceDate + 1.year),
      )
      val subs = toSubscription(isCancelled = false)(plans)
      val result = GetCurrentPlans(subs, referenceDate)
        .map(listOfPlans =>
          listOfPlans.size == 1 &&
            listOfPlans.head.productName == "Supporter Plus",
        )
      result mustEqual \/-(true)
    }

    "tell you that you aren't a contributor immediately after your sub has been cancelled" in {
      val plans = NonEmptyList(contributorPlan(referenceDate, referenceDate + 1.year))
      val subs = toSubscription(isCancelled = true)(plans)
      val result = GetCurrentPlans(subs, referenceDate).leftMap(_.contains("cancelled"))
      result mustEqual -\/(true)
    }

    "tell you a upgraded plan is current on the change date" in {
      val plans = NonEmptyList(supporterPlan(referenceDate.minusDays(4), referenceDate), partnerPlan(referenceDate, referenceDate + 1.year))
      val subs = toSubscription(isCancelled = false)(plans)
      val result = GetCurrentPlans(subs, referenceDate).map(_.head.id.get)
      result mustEqual \/-("idPartner")
    }

    "tell you you aren't a staff membership if your subscription is cancelled regardless of the date" in {
      val plans = NonEmptyList(staffPlan(referenceDate, referenceDate + 1.year))
      val subs = toSubscription(isCancelled = true)(plans)
      val result = GetCurrentPlans(subs, referenceDate).leftMap(_.contains("cancelled"))
      result mustEqual -\/(true) // not helpful
    }

    "tell you you are still a supporter if your subscription is cancelled but it's still before the date" in {
      val plans = NonEmptyList(supporterPlan(referenceDate, referenceDate + 1.year))
      val subs = toSubscription(isCancelled = true)(plans)
      val result = GetCurrentPlans(subs, referenceDate).map(_.head.id.get)
      result mustEqual \/-("idSupporter")
    }

    "tell you you are no longer a supporter if your subscription is after an end date" in {
      val plans = NonEmptyList(supporterPlan(referenceDate - 1.year, referenceDate))
      val subs = toSubscription(isCancelled = false)(plans)
      val result = GetCurrentPlans(subs, referenceDate + 1.day).leftMap(_.contains("ended"))
      result mustEqual -\/(true) // not helpful
    }

    "if you've cancelled and then signed up with a different tier, should return the new tier on day 1" in {
      val plans = NonEmptyList(partnerPlan(referenceDate, referenceDate - 1.year), supporterPlan(referenceDate + 1.day, referenceDate + 1.year))
      val subs = toSubscription(isCancelled = false)(plans)
      val result = GetCurrentPlans(subs, referenceDate + 1.day).map(_.head.id.get)
      result mustEqual \/-("idSupporter")
    }

    "if you're in a free trial of digipack, tell you you're already a digipack subscriber" in {

      val firstPayment = referenceDate + 14.days
      val digipackSub = toSubscription(isCancelled = false)(NonEmptyList(digipackPlan(firstPayment, referenceDate + 1.year)))
        .copy(termStartDate = referenceDate, startDate = referenceDate)

      GetCurrentPlans(digipackSub, referenceDate).map(_.head.id.get) mustEqual \/-("idDigipack")
    }

  }

  "Subscription service" should {

    "Be able to fetch a staff subscription" in {
      val sub = service.get[SubscriptionPlan.AnyPlan](memsub.Subscription.Name("1234"))
      sub.map(_.plan) mustEqual Some(
        FreeSubscriptionPlan(
          RatePlanId("id"),
          prpId,
          "foo",
          "",
          "Friend",
          "Membership",
          Product.Membership,
          FreeCharge(Staff, Set(GBP)),
          start = 12 Aug 2016,
          end = 13 Aug 2016,
        ),
      )
      sub.map(_.name) mustEqual Some(memsub.Subscription.Name("subscriptionNumber"))
    }

    "Give you back a none in the event of the sub not existing" in {
      service.get[SubscriptionPlan.AnyPlan](memsub.Subscription.Name("foo")) mustEqual None
    }

    val contact = new ContactId {
      def salesforceContactId = "foo"
      def salesforceAccountId = "bar"
    }

    "Leverage the soap client to fetch subs by contact ID" in {
      val subs = service.current[SubscriptionPlan.AnyPlan](contact)
      subs.headOption.map(_.name) mustEqual Some(memsub.Subscription.Name("subscriptionNumber")) // what is in the config file
    }

    "Be able to fetch subs where term ends after the specified date" in {
      val currentSubs = service.current[SubscriptionPlan.AnyPlan](contact)
      val sinceSubs = service.since[SubscriptionPlan.AnyPlan](1 Jan 2020)(contact)
      currentSubs mustNotEqual sinceSubs
      sinceSubs.length mustEqual 0 // because no subscriptions have a term end date AFTER 1 Jan 2020
    }

    val referenceDate = 15 Aug 2017

    "Allow you to fetch an upgraded subscription" in {
      service.get[SubscriptionPlan.Partner](Name("A-S00063478")).map(GetCurrentPlans(_, referenceDate).map(_.head.charges.benefit)) mustEqual Some(
        \/-(Partner),
      )
    }

    "Decided cancellation effective date should be None if within lead time period before first fulfilment date" in {
      service.decideCancellationEffectiveDate[AnyPlan](Name("A-lead-time")).run mustEqual \/.right(None)
    }

    "Deciding cancellation effective date should error because Invoiced period has started today, however Bill Run has not yet completed" in {
      service
        .decideCancellationEffectiveDate[AnyPlan](Name("GW-before-bill-run"), LocalTime.parse("01:00"), LocalDate.parse("2020-10-02"))
        .run mustEqual \/.left("Invoiced period has started today, however Bill Run has not yet completed (it usually runs around 6am)")
    }

    "Decided cancellation effective date should be end of 6-for-6 invoiced period if user has started 6-for-6" in {
      service.decideCancellationEffectiveDate[AnyPlan](Name("A-segment-6for6"), today = LocalDate.parse("2020-10-02")).run mustEqual \/.right(
        Some(LocalDate.parse("2020-11-13")),
      )
    }

    "Deciding cancellation effective date should error because Invoiced period exists, and Bill Run has completed, but today is after end of invoice date" in {
      service
        .decideCancellationEffectiveDate[AnyPlan](
          Name("GW-stale-chargeThroughDate"),
          service.BillRunCompletedByTime.plusHours(1),
          LocalDate.parse("2020-06-20").plusDays(1),
        )
        .run mustEqual \/.left(
        "chargedThroughDate exists but seems out-of-date because bill run should have moved chargedThroughDate to next invoice period. Investigate ASAP!",
      )
    }
  }
}
