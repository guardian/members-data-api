package com.gu.memsub.subsv2.services

import com.github.nscala_time.time.Implicits._
import com.github.nscala_time.time.Imports.LocalTime
import com.gu.i18n.Currency.{EUR, GBP}
import com.gu.lib.DateDSL._
import com.gu.memsub
import com.gu.memsub.Subscription.{Id => _, _}
import com.gu.memsub.subsv2._
import com.gu.memsub.subsv2.reads.PlanWithCreditsTestData
import com.gu.memsub.{Subscription => _, _}
import com.gu.monitoring.SafeLogger
import com.gu.okhttp.RequestRunners.HttpClient
import com.gu.salesforce.ContactId
import com.gu.zuora.rest.SimpleClient
import com.gu.zuora.{SoapClient, ZuoraRestConfig}
import io.lemonlabs.uri.typesafe.dsl._
import okhttp3._
import org.joda.time.LocalDate
import org.specs2.mutable.Specification
import scalaz.Id._
import scalaz.{-\/, NonEmptyList, \/, \/-}
import utils.Resource
import utils.TestLogPrefix.testLogPrefix

/** This test just tests plumbing really but at least it is /possible/ to test plumbing
  */
class SubscriptionServiceTest extends Specification {

  import TestCatalog._

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
        case "/subscriptions/credit" => jsonResponse("rest/plans/Credits.json")(request)
        case "/subscriptions/A-S00063478" => jsonResponse("rest/plans/Upgraded.json")(request)
        case "/subscriptions/A-lead-time" => jsonResponse("rest/cancellation/GW-6for6-lead-time.json")(request)
        case "/subscriptions/A-segment-6for6" => jsonResponse("rest/cancellation/GW-6for6-segment-6for6.json")(request)
        case "/subscriptions/GW-before-bill-run" => jsonResponse("rest/cancellation/GW-before-bill-run.json")(request)
        case "/subscriptions/GW-stale-chargeThroughDate" => jsonResponse("rest/cancellation/GW-stale-chargeThroughDate.json")(request)
        case _ => new Response.Builder().message("test").code(404).protocol(Protocol.HTTP_1_0).request(request).build()
      }
  }

  val rc = new SimpleClient[Id](ZuoraRestConfig("TESTS", "https://localhost", "foo", "bar"), subscriptions)
  private val service = new SubscriptionService[Id](catalog, rc, soapClient)

  "Current Plan" should {

    def contributorPlan(startDate: LocalDate, endDate: LocalDate, lastChangeType: Option[String] = None): RatePlan = {
      RatePlan(
        RatePlanId("idContributor"),
        contributorPrpId,
        "Contributor",
        lastChangeType,
        List.empty,
        NonEmptyList(
          RatePlanCharge(
            SubscriptionRatePlanChargeId("noo"),
            ProductRatePlanChargeIds.contributorChargeId,
            PricingSummary(Map(GBP -> Price(5.0f, GBP))),
            Some(ZMonth),
            None,
            SubscriptionEnd,
            None,
            None,
            None,
            startDate,
            endDate,
          ),
        ),
      )
    }
    def partnerPlan(startDate: LocalDate, endDate: LocalDate): RatePlan =
      RatePlan(
        RatePlanId("idPartner"),
        partnerPrpId,
        "Partner",
        None,
        List.empty,
        NonEmptyList(
          RatePlanCharge(
            SubscriptionRatePlanChargeId("noo"),
            ProductRatePlanChargeId("foo"),
            PricingSummary(Map(GBP -> Price(149.0f, GBP))),
            Some(ZYear),
            None,
            SubscriptionEnd,
            None,
            None,
            None,
            startDate,
            endDate,
          ),
        ),
      )
    def supporterPlan(startDate: LocalDate, endDate: LocalDate): RatePlan =
      RatePlan(
        RatePlanId("idSupporter"),
        supporterPrpId,
        "Supporter",
        None,
        List.empty,
        NonEmptyList(
          RatePlanCharge(
            SubscriptionRatePlanChargeId("nar"),
            ProductRatePlanChargeId("bar"),
            PricingSummary(Map(GBP -> Price(49.0f, GBP))),
            Some(ZYear),
            None,
            SubscriptionEnd,
            None,
            None,
            None,
            startDate,
            endDate,
          ),
        ),
      )
    def digipackPlan(startDate: LocalDate, endDate: LocalDate): RatePlan =
      RatePlan(
        RatePlanId("idDigipack"),
        digipackAnnualPrpId,
        "Digital Pack",
        None,
        List.empty,
        NonEmptyList(
          RatePlanCharge(
            SubscriptionRatePlanChargeId("naz"),
            ProductRatePlanChargeId("baz"),
            PricingSummary(Map(GBP -> Price(119.90f, GBP))),
            Some(ZYear),
            None,
            SubscriptionEnd,
            None,
            None,
            None,
            startDate,
            endDate,
          ),
        ),
      )

    def switchedSupporterPlusPlan(startDate: LocalDate, endDate: LocalDate): RatePlan =
      RatePlan(
        id = RatePlanId("idSupporterPlus"),
        productRatePlanId = supporterPlusPrpId,
        productName = "Supporter Plus",
        lastChangeType = Some("Add"),
        features = List.empty,
        ratePlanCharges = NonEmptyList(
          RatePlanCharge(
            SubscriptionRatePlanChargeId("naz"),
            ProductRatePlanChargeId("baz"),
            PricingSummary(Map(GBP -> Price(119.90f, GBP))),
            Some(ZYear),
            None,
            SubscriptionEnd,
            None,
            None,
            chargedThroughDate = None,
            effectiveStartDate = startDate,
            effectiveEndDate = endDate,
          ),
        ),
      )

    def toSubscription(isCancelled: Boolean)(plans: NonEmptyList[RatePlan]): Subscription = {
      import com.gu.memsub.Subscription._
      Subscription(
        id = Id(plans.head.id.get),
        subscriptionNumber = SubscriptionNumber("AS-123123"),
        accountId = AccountId("accountId"),
        contractEffectiveDate = plans.head.effectiveStartDate,
        customerAcceptanceDate = plans.head.effectiveStartDate,
        termEndDate = plans.head.effectiveStartDate + 1.year,
        isCancelled = isCancelled,
        ratePlans = plans.list.toList,
        readerType = ReaderType.Direct,
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
      val result = GetCurrentPlans
        .currentPlans(subs, referenceDate, catalog)
        .map(listOfPlans =>
          listOfPlans.size == 1 &&
            listOfPlans.head.productName == "Supporter Plus",
        )
      result mustEqual \/-(true)
    }

    "tell you that you aren't a contributor immediately after your sub has been cancelled" in {
      val plans = NonEmptyList(contributorPlan(referenceDate, referenceDate + 1.year))
      val subs = toSubscription(isCancelled = true)(plans)
      val result = GetCurrentPlans.currentPlans(subs, referenceDate, catalog).leftMap(_.contains("cancelled"))
      result mustEqual -\/(true)
    }

    "tell you a upgraded plan is current on the change date" in {
      val plans = NonEmptyList(supporterPlan(referenceDate.minusDays(4), referenceDate), partnerPlan(referenceDate, referenceDate + 1.year))
      val subs = toSubscription(isCancelled = false)(plans)
      val result = GetCurrentPlans.currentPlans(subs, referenceDate, catalog).map(_.head.id.get)
      result mustEqual \/-("idPartner")
    }

    "tell you you are still a supporter if your subscription is cancelled but it's still before the date" in {
      val plans = NonEmptyList(supporterPlan(referenceDate, referenceDate + 1.year))
      val subs = toSubscription(isCancelled = true)(plans)
      val result = GetCurrentPlans.currentPlans(subs, referenceDate, catalog).map(_.head.id.get)
      result mustEqual \/-("idSupporter")
    }

    "tell you you are no longer a supporter if your subscription is after an end date" in {
      val plans = NonEmptyList(supporterPlan(referenceDate - 1.year, referenceDate))
      val subs = toSubscription(isCancelled = false)(plans)
      val result = GetCurrentPlans.currentPlans(subs, referenceDate + 1.day, catalog).leftMap(_.contains("ended"))
      result mustEqual -\/(true) // not helpful
    }

    "if you've cancelled and then signed up with a different tier, should return the new tier on day 1" in {
      val plans = NonEmptyList(partnerPlan(referenceDate, referenceDate - 1.year), supporterPlan(referenceDate + 1.day, referenceDate + 1.year))
      val subs = toSubscription(isCancelled = false)(plans)
      val result = GetCurrentPlans.currentPlans(subs, referenceDate + 1.day, catalog).map(_.head.id.get)
      result mustEqual \/-("idSupporter")
    }

    "if you're in a free trial of digipack, tell you you're already a digipack subscriber" in {

      val firstPayment = referenceDate + 14.days
      val digipackSub = toSubscription(isCancelled = false)(NonEmptyList(digipackPlan(firstPayment, referenceDate + 1.year)))
        .copy(contractEffectiveDate = referenceDate)

      GetCurrentPlans.currentPlans(digipackSub, referenceDate, catalog).map(_.head.id.get) mustEqual \/-("idDigipack")
    }

  }

  "Subscription service" should {

    "Be able to fetch a supporter plus subscription" in {
      val sub = service.get(memsub.Subscription.SubscriptionNumber("1234"))
      sub.map(_.plan(catalog)) must beSome(
        RatePlan(
          RatePlanId("8ad08ae28f9570f0018f958813ed10ca"),
          supporterPlusPrpId,
          "Supporter Plus",
          None,
          Nil,
          NonEmptyList(
            RatePlanCharge(
              SubscriptionRatePlanChargeId("8ad08ae28f9570f0018f9588141d10df"),
              ProductRatePlanChargeId("8ad08cbd8586721c01858804e3715378"),
              PricingSummary(Map(EUR -> Price(10.0f, EUR))),
              Some(ZMonth),
              None,
              SubscriptionEnd,
              None,
              None,
              Some(20 Jun 2024),
              20 May 2024,
              20 May 2025,
            ),
            RatePlanCharge(
              SubscriptionRatePlanChargeId("8ad08ae28f9570f0018f9588142410e0"),
              ProductRatePlanChargeId("8ad09ea0858682bb0185880ac57f4c4c"),
              PricingSummary(Map(EUR -> Price(0.0f, EUR))),
              Some(ZMonth),
              None,
              SubscriptionEnd,
              None,
              None,
              Some(20 Jun 2024),
              20 May 2024,
              20 May 2025,
            ),
          ),
        ),
      )
      sub.map(_.subscriptionNumber.getNumber) must beSome("A-S00890520")
    }

    "Be able to fetch a subscription with credits" in {
      val sub = service.get(memsub.Subscription.SubscriptionNumber("credit"))
      sub.map(_.plan(catalog)) must beSome(
        PlanWithCreditsTestData.mainPlan,
      )
      sub.map(_.subscriptionNumber.getNumber) must beSome("A-S00897035")
    }

    "Give you back a none in the event of the sub not existing" in {
      service.get(memsub.Subscription.SubscriptionNumber("foo")) must beNone
    }

    val contact = new ContactId {
      def salesforceContactId = "foo"
      def salesforceAccountId = "bar"
    }

    "Leverage the soap client to fetch subs by contact ID" in {
      val subs = service.current(contact)
      subs.map(_.subscriptionNumber.getNumber).sorted mustEqual List("A-S00890520", "A-S00890521") // from the test resources jsons
    }

    "Be able to fetch subs where term ends after the specified date" in {
      val currentSubs = service.current(contact)
      val sinceSubs = service.since(1 Jun 2025)(contact)
      currentSubs mustNotEqual sinceSubs
      sinceSubs.length mustEqual 0 // because no subscriptions have a term end date AFTER 1 Jun 2025
    }

    val referenceDate = 15 Aug 2017

    "Allow you to fetch an upgraded subscription" in {
      service
        .get(SubscriptionNumber("A-S00063478"))
        .map(GetCurrentPlans.currentPlans(_, referenceDate, catalog).map(_.map(_.name(catalog)))) must beSome(
        \/-(NonEmptyList("Partner")),
      )
    }

    "Decided cancellation effective date should be None if within lead time period before first fulfilment date" in {
      service.decideCancellationEffectiveDate(SubscriptionNumber("A-lead-time")).run mustEqual \/.right(None)
    }

    "Deciding cancellation effective date should error because Invoiced period has started today, however Bill Run has not yet completed" in {
      service
        .decideCancellationEffectiveDate(SubscriptionNumber("GW-before-bill-run"), LocalTime.parse("01:00"), LocalDate.parse("2020-10-02"))
        .run mustEqual \/.left("Invoiced period has started today, however Bill Run has not yet completed (it usually runs around 6am)")
    }

    "Decided cancellation effective date should be end of 6-for-6 invoiced period if user has started 6-for-6" in {
      service.decideCancellationEffectiveDate(SubscriptionNumber("A-segment-6for6"), today = LocalDate.parse("2020-10-02")).run mustEqual \/.right(
        Some(LocalDate.parse("2020-11-13")),
      )
    }

    "Deciding cancellation effective date should error because Invoiced period exists, and Bill Run has completed, but today is after end of invoice date" in {
      service
        .decideCancellationEffectiveDate(
          SubscriptionNumber("GW-stale-chargeThroughDate"),
          service.BillRunCompletedByTime.plusHours(1),
          LocalDate.parse("2020-06-20").plusDays(1),
        )
        .run mustEqual \/.left(
        "chargedThroughDate exists but seems out-of-date because bill run should have moved chargedThroughDate to next invoice period. Investigate ASAP!",
      )
    }
  }
}
