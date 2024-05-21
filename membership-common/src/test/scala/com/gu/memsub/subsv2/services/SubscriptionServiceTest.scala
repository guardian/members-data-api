package com.gu.memsub.subsv2.services

import com.github.nscala_time.time.Implicits._
import com.github.nscala_time.time.Imports.LocalTime
import com.gu.i18n.Currency.{EUR, GBP}
import com.gu.lib.DateDSL._
import com.gu.memsub
import com.gu.memsub.Benefit._
import com.gu.memsub.Subscription.{Id => _, _}
import com.gu.memsub.subsv2.Fixtures._
import com.gu.memsub.subsv2._
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

  val digipackAnnualPrpId = ProductRatePlanId("2c92c0f94bbffaaa014bc6a4212e205b")
  val partnerPrpId = ProductRatePlanId("2c92c0f84c510081014c569327003593")
  val supporterPlusPrpId = ProductRatePlanId("8ad08cbd8586721c01858804e3275376")
  val digipackPrpId = ProductRatePlanId("2c92c0f94f2acf73014f2c908f671591")
  val gw6for6PrpId = ProductRatePlanId("2c92c0f965f212210165f69b94c92d66")
  val gw = ProductRatePlanId("2c92c0f965dc30640165f150c0956859")
  val now = 27 Sep 2016

  val cat = Map[ProductRatePlanId, CatalogZuoraPlan](
    digipackAnnualPrpId -> CatalogZuoraPlan(
      digipackAnnualPrpId,
      "foo",
      "",
      productIds.digipack,
      None,
      List.empty,
      Map(ProductRatePlanChargeId("2c92c0f94bbffaaa014bc6a4213e205d") -> Digipack),
      Status.current,
      None,
      Some("type"),
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
    supporterPlusPrpId -> CatalogZuoraPlan(
      supporterPlusPrpId,
      "Supporter Plus",
      "",
      productIds.supporter,
      None,
      List.empty,
      Map(ProductRatePlanChargeId("8ad08cbd8586721c01858804e3715378") -> SupporterPlus),
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
        case "/subscriptions/accounts/foo" => jsonResponse("rest/plans/accounts/SPlus.json")(request)
        case "/subscriptions/accounts/bar" => jsonResponse("rest/plans/accounts/Digi.json")(request)
        case "/subscriptions/1234" => jsonResponse("rest/plans/SPlus.json")(request)
        case "/subscriptions/A-S00063478" => jsonResponse("rest/plans/Upgraded.json")(request)
        case "/subscriptions/A-lead-time" => jsonResponse("rest/cancellation/GW-6for6-lead-time.json")(request)
        case "/subscriptions/A-segment-6for6" => jsonResponse("rest/cancellation/GW-6for6-segment-6for6.json")(request)
        case "/subscriptions/GW-before-bill-run" => jsonResponse("rest/cancellation/GW-before-bill-run.json")(request)
        case "/subscriptions/GW-stale-chargeThroughDate" => jsonResponse("rest/cancellation/GW-stale-chargeThroughDate.json")(request)
        case _ => new Response.Builder().message("test").code(404).protocol(Protocol.HTTP_1_0).request(request).build()
      }
  }

  val rc = new SimpleClient[Id](ZuoraRestConfig("TESTS", "https://localhost", "foo", "bar"), subscriptions)
  private val service = new SubscriptionService[Id](Fixtures.productIds, cat, rc, soapClient)

  "Current Plan" should {

    def contributorPlan(startDate: LocalDate, endDate: LocalDate, lastChangeType: Option[String] = None): RatePlan =
      RatePlan(
        RatePlanId("idContributor"),
        ProductRatePlanId("prpi"),
        "Contributor",
        "desc",
        "Contributor",
        lastChangeType,
        "Contribution",
        Product.Contribution,
        List.empty,
        RatePlanCharge(
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
    def partnerPlan(startDate: LocalDate, endDate: LocalDate): RatePlan =
      RatePlan(
        RatePlanId("idPartner"),
        ProductRatePlanId("prpi"),
        "Partner",
        "desc",
        "Partner",
        None,
        "Membership",
        Product.Membership,
        List.empty,
        RatePlanCharge(
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
    def supporterPlan(startDate: LocalDate, endDate: LocalDate): RatePlan =
      RatePlan(
        RatePlanId("idSupporter"),
        ProductRatePlanId("prpi"),
        "Supporter",
        "desc",
        "Supporter",
        None,
        "Membership",
        Product.Membership,
        List.empty,
        RatePlanCharge(
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
    def digipackPlan(startDate: LocalDate, endDate: LocalDate): RatePlan =
      RatePlan(
        RatePlanId("idDigipack"),
        ProductRatePlanId("prpi"),
        "Digipack",
        "desc",
        "Digital Pack",
        None,
        "Digital Pack",
        Product.Digipack,
        List.empty,
        RatePlanCharge(
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

    def switchedSupporterPlusPlan(startDate: LocalDate, endDate: LocalDate): RatePlan =
      RatePlan(
        id = RatePlanId("idSupporterPlus"),
        productRatePlanId = ProductRatePlanId("prpi"),
        name = "SupporterPlus",
        description = "desc",
        productName = "Supporter Plus",
        lastChangeType = Some("Add"),
        productType = "Supporter Plus",
        product = Product.SupporterPlus,
        features = List.empty,
        charges = RatePlanCharge(
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

    def toSubscription(isCancelled: Boolean)(plans: NonEmptyList[RatePlan]): Subscription = {
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

    "Be able to fetch a supporter plus subscription" in {
      val sub = service.get(memsub.Subscription.Name("1234"))
      sub.map(_.plan) must beSome(
        RatePlan(
          RatePlanId("8ad08ae28f9570f0018f958813ed10ca"),
          supporterPlusPrpId,
          "Supporter Plus",
          "",
          "Supporter Plus",
          None,
          "type",
          Product.Membership,
          Nil,
          SupporterPlusCharges(
            BillingPeriod.Month,
            List(PricingSummary(Map(EUR -> Price(10.0f, EUR))), PricingSummary(Map(EUR -> Price(0.0f, EUR)))),
          ),
          Some(20 Jun 2024),
          20 May 2024,
          20 May 2025,
        ),
      )
      sub.map(_.name.get) must beSome("A-S00890520")
    }
    "Give you back a none in the event of the sub not existing" in {
      service.get(memsub.Subscription.Name("foo")) must beNone
    }

    val contact = new ContactId {
      def salesforceContactId = "foo"
      def salesforceAccountId = "bar"
    }

    "Leverage the soap client to fetch subs by contact ID" in {
      val subs = service.current(contact)
      subs.map(_.name.get).sorted mustEqual List("A-S00890520", "A-S00890521") // from the test resources jsons
    }

    "Be able to fetch subs where term ends after the specified date" in {
      val currentSubs = service.current(contact)
      val sinceSubs = service.since(1 Jun 2025)(contact)
      currentSubs mustNotEqual sinceSubs
      sinceSubs.length mustEqual 0 // because no subscriptions have a term end date AFTER 1 Jun 2025
    }

    val referenceDate = 15 Aug 2017

    "Allow you to fetch an upgraded subscription" in {
      service.get(Name("A-S00063478")).map(GetCurrentPlans(_, referenceDate).map(_.head.charges.benefits)) must beSome(
        \/-(NonEmptyList(Partner)),
      )
    }

    "Decided cancellation effective date should be None if within lead time period before first fulfilment date" in {
      service.decideCancellationEffectiveDate(Name("A-lead-time")).run mustEqual \/.right(None)
    }

    "Deciding cancellation effective date should error because Invoiced period has started today, however Bill Run has not yet completed" in {
      service
        .decideCancellationEffectiveDate(Name("GW-before-bill-run"), LocalTime.parse("01:00"), LocalDate.parse("2020-10-02"))
        .run mustEqual \/.left("Invoiced period has started today, however Bill Run has not yet completed (it usually runs around 6am)")
    }

    "Decided cancellation effective date should be end of 6-for-6 invoiced period if user has started 6-for-6" in {
      service.decideCancellationEffectiveDate(Name("A-segment-6for6"), today = LocalDate.parse("2020-10-02")).run mustEqual \/.right(
        Some(LocalDate.parse("2020-11-13")),
      )
    }

    "Deciding cancellation effective date should error because Invoiced period exists, and Bill Run has completed, but today is after end of invoice date" in {
      service
        .decideCancellationEffectiveDate(
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
