package services

import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.zuora.ZuoraRestService.{AccountIdRecord, QueryResponse}
import models.Attributes
import org.joda.time.LocalDate
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import testdata.SubscriptionTestData

import scala.concurrent.Future
import scalaz.\/


class ZuoraAttributeServiceTest(implicit ee: ExecutionEnv) extends Specification with Mockito with SubscriptionTestData {

  override def referenceDate = new LocalDate(2017, 9, 20)

  val testId = "12345"
  val testAccountId = AccountId("accountId")
  val anotherTestAccountId = AccountId("anotherTestAccountId")
  val joinDate = referenceDate.plusWeeks(1)
  val digitalPackExpirationDate = referenceDate.plusYears(1)
  val oneAccountQueryResponse = QueryResponse(records = List(AccountIdRecord(testAccountId)), size = 1)
  val twoAccountsQueryResponse = QueryResponse(records = List(AccountIdRecord(testAccountId), AccountIdRecord(anotherTestAccountId)), size = 2)
  val contributorAttributes = Attributes(UserId = testId, None, None, None, None, RecurringContributionPaymentPlan = Some("Monthly Contribution"), None, None)

  val friendAttributes = Attributes(UserId = testId, Some("Friend"), None, None, None, None, Some(joinDate), None)
  val supporterAttributes = Attributes(UserId = testId, Some("Supporter"), None, None, None, None, Some(joinDate), None)

  "ZuoraAttributeService" should {

    "attributesFromZuora" should {
      "return attributes for a user who has one subscription" in new contributor {
        val attributes: Future[Option[Attributes]] = zuoraAttributeService.attributesFromZuora(testId)
        attributes must be_==(Some(contributorAttributes)).await
      }

      "return attributes for a user who has many subscriptions" in new contributorDigitalPack {
        val attributes: Future[Option[Attributes]] = zuoraAttributeService.attributesFromZuora(testId)
        attributes must be_==(Some(contributorDigitalPackAttributes)).await
      }

      "get the value of the adfree flag from the dynamo attributes" in new contributorDigitalPack {
        val contributorDigitalPackAdfreeAttributes = contributorDigitalPackAttributes.copy(AdFree = Some(true))
        val outdatedAttributesButWithAdFree = contributorDigitalPackAttributes.copy(DigitalSubscriptionExpiryDate = None, AdFree = Some(true))

        mockScanamoService.get(testId) returns Future.successful(Some(outdatedAttributesButWithAdFree))

        val attributes: Future[Option[Attributes]] = zuoraAttributeService.attributesFromZuora(testId)
        attributes must be_==(Some(contributorDigitalPackAdfreeAttributes)).await
      }

      "return None if the user has no account ids" in new noAccounts {
        val attributes: Future[Option[Attributes]] = zuoraAttributeService.attributesFromZuora(testId)
        attributes must be_==(None).await
      }
    }

    "getSubscriptions" should {
      "get a contributor subscription for a user who is a contributor" in new contributor {
        val subscriptions = zuoraAttributeService.getSubscriptions(List(testAccountId), testId)

        subscriptions must be_==(\/.right(List(contributor))).await
      }

      "get all subscriptions if a user has multiple" in new contributorDigitalPack {
        val subscriptions = zuoraAttributeService.getSubscriptions(List(testAccountId, anotherTestAccountId), testId)

        subscriptions must be_==(\/.right(List(contributor, digipack))).await
      }

      "get an empty list of subscriptions for a user who doesn't have any " in new accountButNoSubscriptions {
        val subscriptions = zuoraAttributeService.getSubscriptions(List(testAccountId), testId)

        subscriptions must be_==(\/.right(Nil)).await
      }

      "return a left with error message if the subscription service returns a left" in new errorWhenGettingSubs {
        val subscriptions = zuoraAttributeService.getSubscriptions(List(testAccountId), testId)

        subscriptions must be_==(\/.left(s"We called Zuora to get subscriptions for a user with identityId $testId but the call failed because $testErrorMessage")).await
      }
    }

    "queryToAccountIds" should {
      "extract an AccountId from a query response" in new contributor {
        val accountIds: List[AccountId] = zuoraAttributeService.queryToAccountIds(oneAccountQueryResponse)
        accountIds === List(testAccountId)
      }

      "return an empty list when no account ids" in new contributor {
        val emptyResponse = QueryResponse(records = Nil, size = 0)
        val accountIds: List[AccountId] = zuoraAttributeService.queryToAccountIds(emptyResponse)
        accountIds === Nil
      }
    }

    "dynamoAndZuoraAgree" should {
      "return true if the fields obtainable from zuora match " in new contributor {
        val fromDynamo = Future.successful(Some(supporterAttributes))
        val fromZuora = Future.successful(Some(supporterAttributes))

        zuoraAttributeService.dynamoAndZuoraAgree(fromDynamo, fromZuora, testId) must be_==(true).await
      }

      "ignore the fields not obtainable from zuora" in new contributor {
        val fromDynamo = Future.successful(Some(supporterAttributes.copy(AdFree = Some(true))))
        val fromZuora = Future.successful(Some(supporterAttributes))

        zuoraAttributeService.dynamoAndZuoraAgree(fromDynamo, fromZuora, testId) must be_==(true).await
      }

      "return false when dynamo is outdated and does not match zuora" in new contributor {
        val fromDynamo = Future.successful(Some(supporterAttributes))
        val fromZuora = Future.successful(Some(friendAttributes))

        zuoraAttributeService.dynamoAndZuoraAgree(fromDynamo, fromZuora, testId) must be_==(false).await
      }
    }

    "attributesWithFlagFromDynamo" should {
      "update return attributes with only adFree status copied from the dynamo attributes" in new contributor {
        val fromDynamo = Future.successful(Some(contributorAttributes.copy(AdFree = Some(true), Tier = Some("Partner"))))
        val fromZuora = Future.successful(Some(contributorAttributes))

        val expectedResult = Some(contributorAttributes.copy(AdFree = Some(true)))

        zuoraAttributeService.attributesWithFlagFromDynamo(fromZuora, fromDynamo) must be_==(expectedResult).await
      }

      "not have an AdFree status either if there isn't one in dynamo" in new contributor {
        val fromDynamo = Future.successful(Some(contributorAttributes.copy(Tier = Some("Partner"))))
        val fromZuora = Future.successful(Some(contributorAttributes))

        zuoraAttributeService.attributesWithFlagFromDynamo(fromZuora, fromDynamo) must be_==(Some(contributorAttributes)).await
      }
    }

  }

  trait contributor extends Scope {
    val mockScanamoService = mock[AttributeService]

    mockScanamoService.get(testId) returns Future.successful(Some(contributorAttributes))

    def identityIdToAccountIds(identityId: String): Future[\/[String, QueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(List(contributor)))

    val zuoraAttributeService = new ZuoraAttributeService(identityIdToAccountIds, subscriptionFromAccountId, mockScanamoService)
  }

  trait contributorDigitalPack extends Scope {
    val mockScanamoService = mock[AttributeService]

    val contributorDigitalPackAttributes = Attributes(UserId = testId, None, None, None, None, RecurringContributionPaymentPlan = Some("Monthly Contribution"), None, DigitalSubscriptionExpiryDate = Some(digitalPackExpirationDate))
    mockScanamoService.get(testId) returns Future.successful(Some(contributorDigitalPackAttributes))

    def identityIdToAccountIds(identityId: String): Future[\/[String, QueryResponse]] = Future.successful(\/.right(twoAccountsQueryResponse))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = {
      Future.successful {
        accountId match {
          case AccountId("accountId") => \/.right(List(contributor))
          case AccountId("anotherTestAccountId") => \/.right(List(digipack))
          case _ => \/.left(s"subscriptions not found for $testId")
        }
      }
    }

    val zuoraAttributeService = new ZuoraAttributeService(identityIdToAccountIds, subscriptionFromAccountId, mockScanamoService)
  }

  trait noAccounts extends Scope {
    val emptyQueryResponse = QueryResponse(records = Nil, size = 0)
    val mockScanamoService = mock[AttributeService]

    mockScanamoService.get(testId) returns Future.successful(Some(contributorAttributes))

    def identityIdToAccountIds(identityId: String): Future[\/[String, QueryResponse]] = Future.successful(\/.right(emptyQueryResponse))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(Nil))

    val zuoraAttributeService = new ZuoraAttributeService(identityIdToAccountIds, subscriptionFromAccountId, mockScanamoService)
  }

  trait accountButNoSubscriptions extends Scope {
    val mockScanamoService = mock[AttributeService]

    def identityIdToAccountIds(identityId: String): Future[\/[String, QueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(Nil))

    val zuoraAttributeService = new ZuoraAttributeService(identityIdToAccountIds, subscriptionFromAccountId, mockScanamoService)
  }

  trait errorWhenGettingSubs extends Scope {
    val mockScanamoService = mock[AttributeService]
    val testErrorMessage = "Something bad happened! D:"

    def identityIdToAccountIds(identityId: String): Future[\/[String, QueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.left(testErrorMessage))

    val zuoraAttributeService = new ZuoraAttributeService(identityIdToAccountIds, subscriptionFromAccountId, mockScanamoService)
  }

}
