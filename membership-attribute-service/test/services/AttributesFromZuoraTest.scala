package services

import services.AttributesFromZuora._
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


class AttributesFromZuoraTest(implicit ee: ExecutionEnv) extends Specification with SubscriptionTestData with Mockito {

  override def referenceDate = new LocalDate(2016, 9, 20)

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

  val mockDynamoAttributesService = mock[AttributeService]

  def dynamoAttributeUpdater(attributes: Attributes) = Future.successful(Right(attributes))

  "ZuoraAttributeService" should {

    "attributesFromZuora" should {
      "return attributes for a user who has many subscriptions" in new contributorDigitalPack {
        mockDynamoAttributesService.get(testId) returns Future.successful(Some(contributorDigitalPackAttributes))

        val attributes: Future[Option[Attributes]] = AttributesFromZuora.getAttributes(testId, identityIdToAccountIds, subscriptionFromAccountId, mockDynamoAttributesService, referenceDate)
        attributes must be_==(Some(contributorDigitalPackAttributes)).await
      }

      "get the value of the adfree flag from the dynamo attributes" in new contributorDigitalPack {
        val contributorDigitalPackAdfreeAttributes = contributorDigitalPackAttributes.copy(AdFree = Some(true))
        val outdatedAttributesButWithAdFree = contributorDigitalPackAttributes.copy(DigitalSubscriptionExpiryDate = None, AdFree = Some(true))

        mockDynamoAttributesService.get(testId) returns Future.successful(Some(outdatedAttributesButWithAdFree))

        val attributes: Future[Option[Attributes]] = AttributesFromZuora.getAttributes(testId, identityIdToAccountIds, subscriptionFromAccountId, mockDynamoAttributesService, referenceDate)
        attributes must be_==(Some(contributorDigitalPackAdfreeAttributes)).await
      }

      "return None if the user has no account ids" in new noAccounts {
        val attributes: Future[Option[Attributes]] = AttributesFromZuora.getAttributes(testId, identityIdToAccountIds, subscriptionFromAccountId, mockDynamoAttributesService, referenceDate)
        attributes must be_==(None).await
      }

      "return the attributes from Dynamo if Zuora query for account ids fails" in new errorWhenGettingAccounts {
        val attributes: Future[Option[Attributes]] = AttributesFromZuora.getAttributes(testId, identityIdToAccountIds, subscriptionFromAccountId, mockDynamoAttributesService, referenceDate)
        attributes must be_==(Some(supporterAttributes)).await
      }

      "return the attributes from Dynamo if Zuora call to get subscriptions fails" in new errorWhenGettingSubs {
        mockDynamoAttributesService.get(testId) returns Future.successful(Some(supporterAttributes))

        val attributes: Future[Option[Attributes]] = AttributesFromZuora.getAttributes(testId, identityIdToAccountIds, subscriptionFromAccountId, mockDynamoAttributesService, referenceDate)
        attributes must be_==(Some(supporterAttributes)).await
      }

      "return None if there are no attributes from Dynamo and the Zuora call to get subscriptions fails" in new errorWhenGettingSubs {
        mockDynamoAttributesService.get(testId) returns Future.successful(None)
        val attributes: Future[Option[Attributes]] = AttributesFromZuora.getAttributes(testId, identityIdToAccountIds, subscriptionFromAccountId, mockDynamoAttributesService, referenceDate)
        attributes must be_==(None).await
      }

      "still return attributes if there aren't any stored in Dynamo" in new contributor {
        val attributes: Future[Option[Attributes]] = AttributesFromZuora.getAttributes(testId, identityIdToAccountIds, subscriptionFromAccountId, mockDynamoAttributesService, referenceDate)
        attributes must be_==(Some(contributorAttributes)).await
      }

    }

    "getSubscriptions" should {
      "get all subscriptions if a user has multiple" in new contributorDigitalPack {
        mockDynamoAttributesService.get(testId) returns Future.successful(Some(contributorDigitalPackAttributes))

        val subscriptions = AttributesFromZuora.getSubscriptions(List(testAccountId, anotherTestAccountId), testId, subscriptionFromAccountId)

        subscriptions must be_==(\/.right(List(contributor, digipack))).await
      }

      "get an empty list of subscriptions for a user who doesn't have any " in new accountButNoSubscriptions {
        val subscriptions = AttributesFromZuora.getSubscriptions(List(testAccountId), testId, subscriptionFromAccountId)

        subscriptions must be_==(\/.right(Nil)).await
      }

      "return a left with error message if the subscription service returns a left" in new errorWhenGettingSubs {
        val subscriptions = AttributesFromZuora.getSubscriptions(List(testAccountId), testId, subscriptionFromAccountId)

        subscriptions must be_==(\/.left(s"We called Zuora to get subscriptions for a user with identityId $testId but the call failed because $testErrorMessage")).await
      }
    }

    "queryToAccountIds" should {
      "extract an AccountId from a query response" in new contributor {
        val accountIds: List[AccountId] = AttributesFromZuora.queryToAccountIds(oneAccountQueryResponse)
        accountIds === List(testAccountId)
      }

      "return an empty list when no account ids" in new contributor {
        val emptyResponse = QueryResponse(records = Nil, size = 0)
        val accountIds: List[AccountId] = AttributesFromZuora.queryToAccountIds(emptyResponse)
        accountIds === Nil
      }
    }

    "dynamoAndZuoraAgree" should {
      "return true if the fields obtainable from zuora match " in new contributor {
        val fromDynamo = Future.successful(Some(supporterAttributes))
        val fromZuora = Future.successful(Some(supporterAttributes))

        AttributesFromZuora.dynamoAndZuoraAgree(fromDynamo, fromZuora, testId) must be_==(true).await
      }

      "ignore the fields not obtainable from zuora" in new contributor {
        val fromDynamo = Future.successful(Some(supporterAttributes.copy(AdFree = Some(true))))
        val fromZuora = Future.successful(Some(supporterAttributes))

        AttributesFromZuora.dynamoAndZuoraAgree(fromDynamo, fromZuora, testId) must be_==(true).await
      }

      "return false when dynamo is outdated and does not match zuora" in new contributor {
        val fromDynamo = Future.successful(Some(supporterAttributes))
        val fromZuora = Future.successful(Some(friendAttributes))

        AttributesFromZuora.dynamoAndZuoraAgree(fromDynamo, fromZuora, testId) must be_==(false).await
      }
    }

    "attributesWithFlagFromDynamo" should {
      "update return attributes with only adFree status copied from the dynamo attributes" in new contributor {
        val fromDynamo = Future.successful(Some(contributorAttributes.copy(AdFree = Some(true), Tier = Some("Partner"))))
        val fromZuora = Future.successful(Some(contributorAttributes))

        val expectedResult = Some(contributorAttributes.copy(AdFree = Some(true)))

        AttributesFromZuora.attributesWithFlagFromDynamo(fromZuora, fromDynamo) must be_==(expectedResult).await
      }

      "not have an AdFree status either if there isn't one in dynamo" in new contributor {
        val fromDynamo = Future.successful(Some(contributorAttributes.copy(Tier = Some("Partner"))))
        val fromZuora = Future.successful(Some(contributorAttributes))

        AttributesFromZuora.attributesWithFlagFromDynamo(fromZuora, fromDynamo) must be_==(Some(contributorAttributes)).await
      }
    }

  }

  trait contributor extends Scope {
    mockDynamoAttributesService.get(testId) returns Future.successful(Some(contributorAttributes))

    def identityIdToAccountIds(identityId: String): Future[\/[String, QueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(List(contributor)))
  }

  trait contributorDigitalPack extends Scope {
    val contributorDigitalPackAttributes = Attributes(UserId = testId, None, None, None, None, RecurringContributionPaymentPlan = Some("Monthly Contribution"), None, DigitalSubscriptionExpiryDate = Some(digitalPackExpirationDate))

    mockDynamoAttributesService.update(contributorDigitalPackAttributes) returns Future.successful(Right(contributorDigitalPackAttributes))

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
  }

  trait noAccounts extends Scope {
    val emptyQueryResponse = QueryResponse(records = Nil, size = 0)

    mockDynamoAttributesService.get(testId) returns Future.successful(None)

    def identityIdToAccountIds(identityId: String): Future[\/[String, QueryResponse]] = Future.successful(\/.right(emptyQueryResponse))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(Nil))
  }

  trait accountButNoSubscriptions extends Scope {
    mockDynamoAttributesService.get(testId) returns Future.successful(None)

    def identityIdToAccountIds(identityId: String): Future[\/[String, QueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(Nil))
  }

  trait errorWhenGettingSubs extends Scope {
    val testErrorMessage = "Something bad happened! D:"

    def identityIdToAccountIds(identityId: String): Future[\/[String, QueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.left(testErrorMessage))
  }

  trait errorWhenGettingAccounts extends Scope {
    val testErrorMessage = "Something bad happened during the zuora query! D:"

    mockDynamoAttributesService.get(testId) returns Future.successful(Some(supporterAttributes))

    def identityIdToAccountIds(identityId: String): Future[\/[String, QueryResponse]] = Future.successful(\/.left(testErrorMessage))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(Nil))
  }

}
