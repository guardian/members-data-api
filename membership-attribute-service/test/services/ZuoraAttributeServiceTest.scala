package services

import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.{ChargeList, Subscription, SubscriptionPlan}
import com.gu.memsub.subsv2.SubscriptionPlan.{AnyPlan, Contributor}
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.memsub.subsv2.services.SubscriptionService
import com.gu.zuora.ZuoraRestService
import com.gu.zuora.ZuoraRestService.{AccountIdRecord, QueryResponse}
import com.gu.zuora.rest.SimpleClient
import models.Attributes
import org.joda.time.LocalDate
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

import scala.concurrent.{Await, Future}
import scalaz.{Disjunction, \/}
import testdata.SubscriptionTestDataHelper._

class ZuoraAttributeServiceTest(implicit ee: ExecutionEnv) extends Specification with Mockito {

  val testId = "12345"
//  val testAccountId = AccountId("AS-123123")
  val testAccountId = AccountId("accountId")
  val joinDate = new LocalDate(2017, 2, 26)
  val oneAccountQueryResponse = QueryResponse(records = List(AccountIdRecord(testAccountId)), size = 1)
  val contributorAttributes = Attributes(UserId = testId, None, None, None, None, RecurringContributionPaymentPlan = Some("Monthly Contributor"), None, None)
  val friendAttributes = Attributes(UserId = testId, Some("Friend"), None, None, None, None, Some(joinDate), None)
  val supporterAttributes = Attributes(UserId = testId, Some("Supporter"), None, None, None, None, Some(joinDate), None)

  "ZuoraAttributeService" should {

    "attributesFromZuora" should {
      "return attributes for a user who has one subscription" in new happyContributorAttributes {
        zuoraAttributeService.attributesFromZuora(testId)
       ok
      }

      "return attributes for a user who has many subscriptions" in {
        ok
      }

      "get the value of the adfree flag from the dynamo attributes" in {
        ok
      }

      "return None and not make additional calls to get subscriptions if the user has no account ids" in {
        ok
      }
    }

    "getSubscriptions" should {
      "get a contributor subscription for a user who is a contributor" in new happyContributorAttributes {
        val subscriptions = zuoraAttributeService.getSubscriptions(List(testAccountId), testId)

        subscriptions === contributor
      }

      "get all subscriptions if a user has multiple" in {
        ok
      }

      "get an empty list of subscriptions for a user who doesn't have any " in {
        ok
      }

      "return a left with error message if the subscription service returns a left" in {
        ok
      }
    }

    "queryToAccountIds" should {
      "extract an AccountId from a query response" in new happyContributorAttributes {
        val accountIds: List[AccountId] = zuoraAttributeService.queryToAccountIds(oneAccountQueryResponse)
        accountIds === List(testAccountId)
      }

      "return an empty list when no account ids" in new happyContributorAttributes {
        val emptyResponse = QueryResponse(records = Nil, size = 0)
        val accountIds: List[AccountId] = zuoraAttributeService.queryToAccountIds(emptyResponse)
        accountIds === Nil
      }
    }

    "dynamoAndZuoraAgree" should {
      "return true if the fields obtainable from zuora match " in new happyContributorAttributes {
        val fromDynamo = Future.successful(Some(supporterAttributes))
        val fromZuora = Future.successful(Some(supporterAttributes))

        zuoraAttributeService.dynamoAndZuoraAgree(fromDynamo, fromZuora, testId) must be_==(true).await
      }

      "ignore the fields not obtainable from zuora" in new happyContributorAttributes {
        val fromDynamo = Future.successful(Some(supporterAttributes.copy(AdFree = Some(true))))
        val fromZuora = Future.successful(Some(supporterAttributes))

        zuoraAttributeService.dynamoAndZuoraAgree(fromDynamo, fromZuora, testId) must be_==(true).await
      }

      "return false when dynamo is outdated and does not match zuora" in new happyContributorAttributes {
        val fromDynamo = Future.successful(Some(supporterAttributes))
        val fromZuora = Future.successful(Some(friendAttributes))

        zuoraAttributeService.dynamoAndZuoraAgree(fromDynamo, fromZuora, testId) must be_==(false).await
      }
    }

    "attributesWithFlagFromDynamo" should {
      "update return attributes with only adFree status copied from the dynamo attributes" in new happyContributorAttributes {
        val fromDynamo = Future.successful(Some(contributorAttributes.copy(AdFree = Some(true), Tier = Some("Partner"))))
        val fromZuora = Future.successful(Some(contributorAttributes))

        val expectedResult = Some(contributorAttributes.copy(AdFree = Some(true)))

        zuoraAttributeService.attributesWithFlagFromDynamo(fromZuora, fromDynamo) must be_==(expectedResult).await
      }

      "not have an AdFree status either if there isn't one in dynamo" in new happyContributorAttributes {
        val fromDynamo = Future.successful(Some(contributorAttributes.copy(Tier = Some("Partner"))))
        val fromZuora = Future.successful(Some(contributorAttributes))

        zuoraAttributeService.attributesWithFlagFromDynamo(fromZuora, fromDynamo) must be_==(Some(contributorAttributes)).await
      }
    }

  }

  trait happyContributorAttributes extends Scope {
    //test Id has one subscription and it's a recurring contribution atm
    implicit val mockSimpleClient = mock[SimpleClient[Future]]
    val mockPatientZuoraRestService = mock[ZuoraRestService[Future]]
    val mockSubscriptionService = mock[SubscriptionService[Future]]
    val mockScanamoService = mock[AttributeService]

    import ZuoraRestService._
    import com.gu.memsub.subsv2.reads.SubPlanReads.anyPlanReads


    def cannedResponse(identityId: String): Future[\/[String, QueryResponse]] = Future.successful(if(identityId == testId) \/.left(s"account ids not found for $testId") else \/.right(oneAccountQueryResponse))
    mockSimpleClient.post[RestQuery, QueryResponse]("action/query", RestQuery(s"select Id from account where IdentityId__c = '$testId'")) returns cannedResponse(testId)
    mockPatientZuoraRestService.getAccounts(testId) returns cannedResponse(testId)

    def cannedSubsResponse(accountId: AccountId) = Future.successful(if(accountId != testAccountId) \/.left(s"subscriptions not found for $testId") else \/.right(List(contributor)))
    implicit def anyPlanMatcher = any[SubPlanReads[AnyPlan]]
    //    mockSubscriptionService.subscriptionsForAccountId[AnyPlan](testAccountId)(anyPlanReads) returns cannedSubsResponse(testAccountId)

    mockSubscriptionService.subscriptionsForAccountId[AnyPlan](any[AccountId])(anyPlanMatcher) returns cannedSubsResponse(testAccountId)

    mockScanamoService.get(testId) returns Future.successful(Some(contributorAttributes))

    val zuoraAttributeService = new ZuoraAttributeService(mockPatientZuoraRestService, mockSubscriptionService, mockScanamoService)

  }

}
