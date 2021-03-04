package services

import java.net.SocketTimeoutException
import com.amazonaws.services.dynamodbv2.model.DeleteItemResult
import com.gu.i18n.Currency.GBP
import com.gu.memsub.Subscription.AccountId
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.gu.memsub.subsv2.reads.SubPlanReads
import com.gu.zuora.rest.ZuoraRestService.{AccountObject, GetAccountsQueryResponse, GiftSubscriptionsFromIdentityIdRecord, GiftSubscriptionsFromIdentityIdResponse, PaymentMethodId, PaymentMethodResponse}
import models.{AccountWithSubscriptions, Attributes, DynamoAttributes, ZuoraAttributes}
import org.joda.time.{DateTime, LocalDate}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.EventuallyMatchers
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeEach, Scope}
import testdata.SubscriptionTestData
import testdata.AccountObjectTestData._
import akka.actor.ActorSystem

import scala.concurrent.duration._
import scala.concurrent.Future
import scalaz.\/
import services.AttributesFromZuora.{attributesDoNotMatch, datesAreEqualEnough}

class AttributesFromZuoraTest(implicit ee: ExecutionEnv) extends Specification with SubscriptionTestData with Mockito with BeforeEach with EventuallyMatchers {
  implicit val as: ActorSystem = ActorSystem("test")

  val attributesFromZuora = new AttributesFromZuora()
  override def referenceDate = new LocalDate(2016, 9, 20)
  val referenceDateInSeconds = referenceDate.toDateTimeAtStartOfDay.getMillis / 1000
  def twoWeeksFromReferenceDate = referenceDate.toDateTimeAtStartOfDay.plusWeeks(2)

  val testId = "12345"
  val testAccountId = AccountId("accountId")
  val anotherTestAccountId = AccountId("anotherTestAccountId")
  val joinDate = referenceDate.plusWeeks(1)
  val digitalPackExpirationDate = referenceDate.plusYears(1)
  val oneAccountQueryResponse = GetAccountsQueryResponse(records = List(AccountObject(testAccountId, 0, Some(GBP))), size = 1)
  val twoAccountsQueryResponse = GetAccountsQueryResponse(records = List(AccountObject(testAccountId, 0, Some(GBP)), AccountObject(anotherTestAccountId, 0, Some(GBP))), size = 2)

  val contributorDynamoAttributes = DynamoAttributes(
    UserId = testId,
    Tier = None,
    RecurringContributionPaymentPlan = Some("Monthly Contribution"),
    MembershipJoinDate = None,
    DigitalSubscriptionExpiryDate = None,
    PaperSubscriptionExpiryDate = None,
    GuardianWeeklySubscriptionExpiryDate = None,
    TTLTimestamp = referenceDateInSeconds
  )
  val contributorAttributes = DynamoAttributes.asAttributes(contributorDynamoAttributes)

  val supporterDynamoAttributes = DynamoAttributes(
    UserId = testId,
    Tier = Some("Supporter"),
    RecurringContributionPaymentPlan = None,
    MembershipJoinDate = Some(referenceDate),
    DigitalSubscriptionExpiryDate = None,
    TTLTimestamp = referenceDateInSeconds
  )

  def asZuoraAttributes(dynamoAttributes: DynamoAttributes): ZuoraAttributes = ZuoraAttributes(
    UserId = dynamoAttributes.UserId,
    Tier = dynamoAttributes.Tier,
    RecurringContributionPaymentPlan = dynamoAttributes.RecurringContributionPaymentPlan,
    MembershipJoinDate = dynamoAttributes.MembershipJoinDate,
    DigitalSubscriptionExpiryDate = dynamoAttributes.DigitalSubscriptionExpiryDate
  )


  val supporterZuoraAttributes = asZuoraAttributes(supporterDynamoAttributes)
  val supporterAttributes = DynamoAttributes.asAttributes(supporterDynamoAttributes)

  val friendAttributes = supporterDynamoAttributes.copy(Tier = Some("friend"))

  def toDynamoTtl(date: DateTime) = date.getMillis / 1000

  val mockDynamoAttributesService = mock[AttributeService]
  val mockSupporterProductDataService = mock[SupporterProductDataService]
  mockSupporterProductDataService.getAttributes(anyString) returns Future.successful(\/.right(Some(Attributes(testId))))

  def dynamoAttributeUpdater(attributes: Attributes) = Future.successful(Right(attributes))

  def paymentMethodResponseNoFailures(id: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(0, "CreditCardReferenceTransaction", referenceDate.toDateTimeAtCurrentTime)))
  def paymentMethodResponseRecentFailure(id: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(1, "CreditCardReferenceTransaction", DateTime.now().minusDays(1))))
  def paymentMethodResponseStaleFailure(id: PaymentMethodId) = Future.successful(\/.right(PaymentMethodResponse(1, "CreditCardReferenceTransaction", DateTime.now().minusMonths(2))))

  def listOfAccountObjectsToGetAccountsQueryResponse(accounts: List[AccountObject]) = GetAccountsQueryResponse(
    records = accounts,
    size = accounts.size
  )

  def nilGiftSubscriptionsFromIdentityId(identityId: String): Future[String \/ List[GiftSubscriptionsFromIdentityIdRecord]] = Future.successful(\/.right(Nil))

  override def before: Unit = {
    org.mockito.Mockito.reset(mockDynamoAttributesService)
  }

  "ZuoraAttributeService" should {

    "attributesFromZuora" should {

      "return attributes for a user who has many subscriptions" in new contributorDigitalPack {
        mockDynamoAttributesService.get(testId) returns Future.successful(Some(dynamoContributorDigitalPackAttributes))

        val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId, nilGiftSubscriptionsFromIdentityId,  paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
        attributes must be_==("Zuora", Some(contributorDigitalPackAttributes)).await

        there was no(mockDynamoAttributesService).delete(anyString)
      }

      "return None if the user has no account ids" in new noAccounts {
        val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId, nilGiftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
        attributes must be_==("Zuora", None).await
      }

      "return a gift Digital Subscription for the giftee" in new noAccounts {
        val termEndDate = new LocalDate(2021, 12, 1)
        val giftSubscriptionAttributes = Attributes(testId, DigitalSubscriptionExpiryDate = Some(termEndDate))
        def giftSubscriptionsFromIdentityId(identityId: String) =
          Future.successful(\/.right(List(GiftSubscriptionsFromIdentityIdRecord("name", "abc123", termEndDate))))

        val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId, giftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
        attributes must be_==("Zuora", Some(giftSubscriptionAttributes)).await
      }

      "return the gift Digital Subscription with the latest expiry date if there is more than one" in new noAccounts {
        val earliestDate = new LocalDate(2021, 12, 1)
        val latestDate = earliestDate.plusMonths(3)
        val giftSubscriptionAttributes = Attributes(testId, DigitalSubscriptionExpiryDate = Some(latestDate))
        def giftSubscriptionsFromIdentityId(identityId: String) =
          Future.successful(\/.right(List(
            GiftSubscriptionsFromIdentityIdRecord("name", "abc123", earliestDate),
            GiftSubscriptionsFromIdentityIdRecord("name", "abc123", latestDate),
            GiftSubscriptionsFromIdentityIdRecord("name", "abc123", earliestDate)
          )))

        val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId, giftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
        attributes must be_==("Zuora", Some(giftSubscriptionAttributes)).await
      }

      "NOT return a gift Digital Subscription for the gifter" in new giftDigiSub {
        val termEndDate = new LocalDate(2021, 12, 1)
        val attributesWithNoDigitalSub = Attributes(testId)

        val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId, nilGiftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
        attributes must be_==("Zuora", None).await
      }

      "return the attributes from Dynamo if Zuora query for account ids fails" in new errorWhenGettingAccounts {
        val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId,  nilGiftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
        attributes must be_==("Dynamo", Some(supporterAttributes)).await

        there was no(mockDynamoAttributesService).delete(anyString)
      }

      "return the attributes from Dynamo if Zuora call to get subscriptions fails" in new errorWhenGettingSubs {
        mockDynamoAttributesService.get(testId) returns Future.successful(Some(supporterDynamoAttributes))

        val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId,  nilGiftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
        attributes must be_==("Dynamo", Some(supporterAttributes)).await

        there was no(mockDynamoAttributesService).delete(anyString)
      }

      "return None if there are no attributes from Dynamo and the Zuora call to get subscriptions fails" in new errorWhenGettingSubs {
        mockDynamoAttributesService.get(testId) returns Future.successful(None)
        val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId,  nilGiftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
        attributes must be_==("Dynamo", None).await

        there was no(mockDynamoAttributesService).delete(anyString)
      }

      "return a response from the cache if a call to Zuora returns a left" in {
        List(Some(contributorDynamoAttributes), None) map { attributesFromCache =>
          val testErrorMessage = "Something bad happened! D:"

          def identityIdToAccountIds(identityId: String): Future[\/[String, GetAccountsQueryResponse]] = Future.successful(\/.left(testErrorMessage))
          def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.left(testErrorMessage))

          mockDynamoAttributesService.get(testId) returns Future.successful(attributesFromCache)

          val expected = attributesFromCache flatMap {attr => Some(DynamoAttributes.asAttributes(attr))}

          val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId,  nilGiftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
          attributes must be_==("Dynamo", expected).await
        }

      }

      "return a response from the cache if there is networking outage calling Zuora" in {
        List(Some(contributorDynamoAttributes), None) map { attributesFromCache =>
          def identityIdToAccountIds(identityId: String) = Future.failed(new SocketTimeoutException("boom"))
          def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.failed(new SocketTimeoutException("boom"))
          mockDynamoAttributesService.get(testId) returns Future.successful(attributesFromCache)
          val expectedAttr = attributesFromCache flatMap { attr => Some(DynamoAttributes.asAttributes(attr)) }
          val actualAttr = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId, nilGiftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
          actualAttr must be_==("Dynamo", expectedAttr).awaitFor(2.second) // waiting for few seconds because there is retry logic on networking failures
        }
      }

      "still return attributes if there aren't any stored in Dynamo" in {
        val contributorAttributesWithTtl = contributorDynamoAttributes.copy(TTLTimestamp = toDynamoTtl(twoWeeksFromReferenceDate))

        mockDynamoAttributesService.get(testId) returns Future.successful(None)
        mockDynamoAttributesService.update(contributorAttributesWithTtl) returns Future.successful(Right(contributorAttributesWithTtl))

        def identityIdToAccountIds(identityId: String): Future[\/[String, GetAccountsQueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))
        def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(List(contributor)))

        val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId, nilGiftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
        attributes must be_==("Zuora", Some(contributorAttributes)).await

        eventually(there was one(mockDynamoAttributesService).update(contributorAttributesWithTtl))
        there was no(mockDynamoAttributesService).delete(anyString)
      }

      "remove the entry from dynamo if zuora attributes are none" in {
        mockDynamoAttributesService.get(testId) returns Future.successful(Some(contributorDynamoAttributes))
        mockDynamoAttributesService.delete(testId) returns Future.successful(new DeleteItemResult())

        def identityIdToAccountIds(identityId: String): Future[\/[String, GetAccountsQueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))
        def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(Nil))

        val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId, nilGiftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)

        attributes must be_==("Zuora", None).await

        eventually(there was one(mockDynamoAttributesService).delete(testId))
      }

      "update the attributes in dynamo if zuora is more up to date even if the ttl is old" in {
        //friend in Dynamo, upgraded in Zuora

        val oldTtlInSeconds = toDynamoTtl(twoWeeksFromReferenceDate.minusDays(10))

        val expectedAttributesWithTtl = supporterDynamoAttributes.copy(TTLTimestamp = toDynamoTtl(twoWeeksFromReferenceDate))
        mockDynamoAttributesService.get(testId) returns Future.successful(Some(friendAttributes.copy(TTLTimestamp = oldTtlInSeconds)))
        mockDynamoAttributesService.update(any[DynamoAttributes]) returns Future.successful(Right(expectedAttributesWithTtl))

        def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(List(membership)))
        def identityIdToAccountIds(identityId: String): Future[\/[String, GetAccountsQueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))

        val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId, nilGiftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
        attributes must be_==("Zuora", Some(supporterAttributes)).await

        eventually(there was one(mockDynamoAttributesService).update(expectedAttributesWithTtl))
        there was no(mockDynamoAttributesService).delete(anyString)
      }

      "update the attributes in dynamo if zuora is more up to date even if the ttl is new enough" in {
        //friend in Dynamo, upgraded in Zuora
        val newEnoughTtl = toDynamoTtl(twoWeeksFromReferenceDate.minusHours(1))

        val expectedAttributesWithTtl = supporterDynamoAttributes.copy(TTLTimestamp = toDynamoTtl(twoWeeksFromReferenceDate))
        mockDynamoAttributesService.get(testId) returns Future.successful(Some(friendAttributes.copy(TTLTimestamp = newEnoughTtl)))
        mockDynamoAttributesService.update(any[DynamoAttributes]) returns Future.successful(Right(expectedAttributesWithTtl))

        def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(List(membership)))
        def identityIdToAccountIds(identityId: String): Future[\/[String, GetAccountsQueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))

        val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId, nilGiftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
        attributes must be_==("Zuora", Some(supporterAttributes)).await

        eventually(there was one(mockDynamoAttributesService).update(expectedAttributesWithTtl))
        there was no(mockDynamoAttributesService).delete(anyString)
      }

      "update the ttl if it is too old even if zuora and dynamo agree" in {
        val oldTtlInSeconds = toDynamoTtl(twoWeeksFromReferenceDate.minusDays(10))

        val expectedAttributesWithUpdatedTtl = supporterDynamoAttributes.copy(TTLTimestamp = toDynamoTtl(twoWeeksFromReferenceDate))
        mockDynamoAttributesService.get(testId) returns Future.successful(Some(supporterDynamoAttributes.copy(TTLTimestamp = oldTtlInSeconds)))
        mockDynamoAttributesService.update(any[DynamoAttributes]) returns Future.successful(Right(expectedAttributesWithUpdatedTtl))

        def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(List(membership)))
        def identityIdToAccountIds(identityId: String): Future[\/[String, GetAccountsQueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))

        val attributes: Future[(String, Option[Attributes])] = attributesFromZuora.getAttributesFromZuoraWithCacheFallback(testId, identityIdToAccountIds, subscriptionFromAccountId, nilGiftSubscriptionsFromIdentityId, paymentMethodResponseNoFailures, mockDynamoAttributesService, mockSupporterProductDataService, referenceDate)
        attributes must be_==("Zuora", Some(supporterAttributes)).await

        eventually(there was one(mockDynamoAttributesService).update(expectedAttributesWithUpdatedTtl))
        there was no(mockDynamoAttributesService).delete(anyString)
      }

      "identify a mismatch between attributes" in {
        val fromZuora = Some(Attributes("123", Some("Supporter")))
        val fromSupporterProductData = Some(Attributes("123"))
        attributesDoNotMatch(fromZuora, fromSupporterProductData) should beTrue
        attributesDoNotMatch(fromZuora, None) should beTrue
        attributesDoNotMatch(None, None) should beFalse
      }

      "Complex attributes should match" in {
        val fromZuora = Some(
          Attributes(
            UserId = "21841960",
            Tier = None,
            RecurringContributionPaymentPlan = Some("Monthly Contribution"),
            OneOffContributionDate = None,
            MembershipJoinDate = None,
            DigitalSubscriptionExpiryDate = Some(LocalDate.parse("2023-02-16")),
            PaperSubscriptionExpiryDate = Some(LocalDate.parse("2023-02-16")),
            GuardianWeeklySubscriptionExpiryDate = Some(LocalDate.parse("2022-10-30")),
            LiveAppSubscriptionExpiryDate = None,
            AlertAvailableFor = None
          ))
        val fromSupporterProductData = Some(
          Attributes(
            UserId = "21841960",
            Tier = None,
            RecurringContributionPaymentPlan = Some("Monthly Contribution"),
            OneOffContributionDate = None,
            MembershipJoinDate = None,
            DigitalSubscriptionExpiryDate = Some(LocalDate.parse("2023-02-17")),
            PaperSubscriptionExpiryDate = Some(LocalDate.parse("2023-02-17")),
            GuardianWeeklySubscriptionExpiryDate = Some(LocalDate.parse("2022-10-31")),
            LiveAppSubscriptionExpiryDate = None,
            AlertAvailableFor = None))
        attributesDoNotMatch(fromZuora, fromSupporterProductData) should beFalse
      }

      "be tolerant of zuora dates being one up to day ahead supporter-product-data dates" in {
        datesAreEqualEnough(
          fromZuora = Some(LocalDate.parse("2022-02-16")),
          fromSupporterProductData = Some(LocalDate.parse("2022-02-17"))
        ) should beTrue

        datesAreEqualEnough(
          fromZuora = None,
          fromSupporterProductData = None
        ) should beTrue

        datesAreEqualEnough(
          fromZuora = Some(LocalDate.parse("2021-03-03")),
          fromSupporterProductData = None
        ) should beFalse

        datesAreEqualEnough(
          fromZuora = None,
          fromSupporterProductData = Some(LocalDate.parse("2021-03-03"))
        ) should beFalse

        datesAreEqualEnough(
          fromZuora = Some(LocalDate.parse("2021-03-04")),
          fromSupporterProductData = Some(LocalDate.parse("2021-03-03"))
        ) should beFalse
      }

      "ignore MembershipJoinDate when comparing attributes" in {
        val fromZuora = Some(Attributes("123", MembershipJoinDate = Some(LocalDate.now())))
        val fromSupporterProductData = Some(Attributes("123"))
        attributesDoNotMatch(fromZuora, fromSupporterProductData) should beFalse
      }

    }

    "getSubscriptions" should {
      "get all subscriptions if a user has multiple accounts with a single subscription" in new contributorDigitalPack {
        mockDynamoAttributesService.get(testId) returns Future.successful(Some(dynamoContributorDigitalPackAttributes))
        val anotherAccountObject = accountObjectWithZeroBalance.copy(Id = anotherTestAccountId)
        val response = listOfAccountObjectsToGetAccountsQueryResponse(List(accountObjectWithZeroBalance, anotherAccountObject))
        val subscriptions = attributesFromZuora.getSubscriptions(response, subscriptionFromAccountId)

        val expected = List(AccountWithSubscriptions(accountObjectWithZeroBalance, List(contributor)), AccountWithSubscriptions(anotherAccountObject, List(digipack)))
        subscriptions must be_==(\/.right(expected)).await
      }

      "get all subscriptions if a user has a single account with multiple subscriptions" in new contributorDigitalPack {
        mockDynamoAttributesService.get(testId) returns Future.successful(Some(dynamoContributorDigitalPackAttributes))
        val anotherAccountObject = accountObjectWithZeroBalance.copy(Id = AccountId("manySubsPerAccount"))
        val response = listOfAccountObjectsToGetAccountsQueryResponse(List(anotherAccountObject))
        val subscriptions = attributesFromZuora.getSubscriptions(response, subscriptionFromAccountId)

        val expected = List(AccountWithSubscriptions(anotherAccountObject, List(contributor, digipack)))
        subscriptions must be_==(\/.right(expected)).await
      }

      "get an empty list of subscriptions for a user who doesn't have any " in new accountButNoSubscriptions {
        val response = listOfAccountObjectsToGetAccountsQueryResponse(List(accountObjectWithZeroBalance))
        val subscriptions = attributesFromZuora.getSubscriptions(response, subscriptionFromAccountId)

        subscriptions must be_==(\/.right(List(AccountWithSubscriptions(accountObjectWithZeroBalance, Nil)))).await
      }

      "return a left with error message if the subscription service returns a left" in new errorWhenGettingSubs {
        val response = listOfAccountObjectsToGetAccountsQueryResponse(List(accountObjectWithZeroBalance))
        val subscriptions = attributesFromZuora.getSubscriptions(response, subscriptionFromAccountId)

        subscriptions must be_==(\/.left(testErrorMessage)).await
      }
    }

    "queryToAccountIds" should {
      "extract an AccountId from a query response" in new contributor {
        val accountIds: List[AccountId] = attributesFromZuora.queryToAccountIds(oneAccountQueryResponse)
        accountIds === List(testAccountId)
      }

      "return an empty list when no account ids" in new contributor {
        val emptyResponse = GetAccountsQueryResponse(records = Nil, size = 0)
        val accountIds: List[AccountId] = attributesFromZuora.queryToAccountIds(emptyResponse)
        accountIds === Nil
      }
    }

    "dynamoAndZuoraAgree" should {
      "ignore the TTL" in {
        val fromDynamo = Some(supporterDynamoAttributes.copy(TTLTimestamp = toDynamoTtl(twoWeeksFromReferenceDate)))
        val fromZuora = Some(asZuoraAttributes(supporterDynamoAttributes))

        attributesFromZuora.dynamoAndZuoraAgree(fromDynamo, fromZuora, testId) must be_==(true)
      }

      "return false when dynamo is outdated and does not match zuora" in {
        val fromDynamo = Some(supporterDynamoAttributes)
        val fromZuora = Some(asZuoraAttributes(friendAttributes))

        attributesFromZuora.dynamoAndZuoraAgree(fromDynamo, fromZuora, testId) must be_==(false)
      }

      "return false if dynamo is none and zuora has attributes" in {
        val fromDynamo = None
        val fromZuora = Some(asZuoraAttributes(supporterDynamoAttributes))

        attributesFromZuora.dynamoAndZuoraAgree(fromDynamo, fromZuora, testId) must be_==(false)
      }

      "return true if dynamo is none and attributes from zuora is also none" in {
        val fromDynamo = None
        val fromZuora = None

        attributesFromZuora.dynamoAndZuoraAgree(fromDynamo, fromZuora, testId) must be_==(true)
      }
    }

    "dynamoUpdateRequired" should {
      "return true if there is no existing timestamp in the Dynamo attributes" in {
        val updateRequired = attributesFromZuora.dynamoUpdateRequired(Some(supporterDynamoAttributes), Some(asZuoraAttributes(supporterDynamoAttributes)), supporterDynamoAttributes.UserId, twoWeeksFromReferenceDate)
        updateRequired must be_==(true)
      }

      "return true if the timestamp is old" in {
        val tooOld = toDynamoTtl(twoWeeksFromReferenceDate.minusDays(14))
        val updateRequired = attributesFromZuora.dynamoUpdateRequired(dynamoAttributes = Some(supporterDynamoAttributes.copy(TTLTimestamp = tooOld)), Some(asZuoraAttributes(supporterDynamoAttributes)), supporterDynamoAttributes.UserId, twoWeeksFromReferenceDate)

        updateRequired must be_==(true)
      }

      "return true if zuora and dynamo disagree and the timestamp is recent" in {
        val recentEnough = toDynamoTtl(twoWeeksFromReferenceDate.minusHours(14))
        val dynamoAttributes = Some(supporterDynamoAttributes.copy(TTLTimestamp = recentEnough))
        val zuoraAttributes = Some(asZuoraAttributes(friendAttributes))

        val updateRequired = attributesFromZuora.dynamoUpdateRequired(dynamoAttributes, zuoraAttributes, "123", twoWeeksFromReferenceDate)

        updateRequired must be_==(true)
      }

      "return false if zuora and dynamo agree and the timestamp is recent enough" in {
        val recentEnough = toDynamoTtl(twoWeeksFromReferenceDate.minusHours(14))
        val updateRequired = attributesFromZuora.dynamoUpdateRequired(dynamoAttributes = Some(supporterDynamoAttributes.copy(TTLTimestamp = recentEnough)), Some(asZuoraAttributes(supporterDynamoAttributes)), supporterDynamoAttributes.UserId, twoWeeksFromReferenceDate)

        updateRequired must be_==(false)
      }

      "return false if there are no attributes in Zuora or Dynamo" in {
        val updateRequired = attributesFromZuora.dynamoUpdateRequired(None, None, supporterDynamoAttributes.UserId, twoWeeksFromReferenceDate)
        updateRequired must be_==(false)
      }

    }

    "mergeDigitalSubscriptionExpiryDate" should {
      "return the latest expiry date if there is both a regular and a gift subscription" in {
        val earlyDate = LocalDate.now()
        val laterDate = earlyDate.plusDays(1)

        val earlyAttributes = Some(ZuoraAttributes("", DigitalSubscriptionExpiryDate = Some(earlyDate)))
        val laterAttributes = Some(ZuoraAttributes("", DigitalSubscriptionExpiryDate = Some(laterDate)))

        AttributesFromZuora.mergeDigitalSubscriptionExpiryDate(earlyAttributes, laterAttributes)
          .get.DigitalSubscriptionExpiryDate.get must be_==(laterDate)

        AttributesFromZuora.mergeDigitalSubscriptionExpiryDate(laterAttributes, earlyAttributes)
          .get.DigitalSubscriptionExpiryDate.get must be_==(laterDate)
      }

      "return an expiry date if there is one of a regular or a gift subscription" in {
        val expiryDate = LocalDate.now().plusDays(1)

        val attributesWithSub = Some(ZuoraAttributes("", DigitalSubscriptionExpiryDate = Some(expiryDate)))
        val attributesWithout = Some(ZuoraAttributes("", DigitalSubscriptionExpiryDate = None, RecurringContributionPaymentPlan = Some("")))

        AttributesFromZuora.mergeDigitalSubscriptionExpiryDate(attributesWithSub, attributesWithout)
          .get.DigitalSubscriptionExpiryDate.get must be_==(expiryDate)

        AttributesFromZuora.mergeDigitalSubscriptionExpiryDate(attributesWithout, attributesWithSub)
          .get.DigitalSubscriptionExpiryDate.get must be_==(expiryDate)
      }

      "return a ZuoraAttributes if either regular or gift attributes are not None" in {
        val expiryDate = LocalDate.now().plusDays(1)

        val regularAttributes = Some(ZuoraAttributes("", PaperSubscriptionExpiryDate = Some(expiryDate)))
        val giftAttributes = Some(ZuoraAttributes("", DigitalSubscriptionExpiryDate = Some(expiryDate)))

        AttributesFromZuora.mergeDigitalSubscriptionExpiryDate(None, giftAttributes)
          .get.DigitalSubscriptionExpiryDate.get must be_==(expiryDate)

        AttributesFromZuora.mergeDigitalSubscriptionExpiryDate(regularAttributes, None)
          .get.PaperSubscriptionExpiryDate.get must be_==(expiryDate)
      }
    }

  }

  trait contributor extends Scope {
    mockDynamoAttributesService.get(testId) returns Future.successful(Some(contributorDynamoAttributes))
    mockDynamoAttributesService.update(contributorDynamoAttributes) returns Future.successful(Right(contributorDynamoAttributes))

    def identityIdToAccountIds(identityId: String): Future[\/[String, GetAccountsQueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))
    def accountIdToAccountObject(accountId: AccountId) = Future.successful(\/.right(accountObjectWithZeroBalance))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(List(contributor)))
    def giftSubscriptionsFromIdentityId(identityId: String) = Future.successful(\/.right(GiftSubscriptionsFromIdentityIdResponse(Nil, 0)))
  }

  trait contributorDigitalPack extends Scope {

    val dynamoContributorDigitalPackAttributes = DynamoAttributes(
      UserId = testId,
      Tier = None,
      RecurringContributionPaymentPlan = Some("Monthly Contribution"),
      MembershipJoinDate = None,
      DigitalSubscriptionExpiryDate = Some(digitalPackExpirationDate),
      TTLTimestamp = toDynamoTtl(twoWeeksFromReferenceDate)
    )
    val zuoraContributorDigitalPackAttributes = asZuoraAttributes(dynamoContributorDigitalPackAttributes)
    val contributorDigitalPackAttributes = DynamoAttributes.asAttributes(dynamoContributorDigitalPackAttributes)

    mockDynamoAttributesService.update(dynamoContributorDigitalPackAttributes) returns Future.successful(Right(dynamoContributorDigitalPackAttributes))

    def identityIdToAccountIds(identityId: String): Future[\/[String, GetAccountsQueryResponse]] = Future.successful(\/.right(twoAccountsQueryResponse))

    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = {
      Future.successful {
        accountId match {
          case AccountId("accountId") => \/.right(List(contributor))
          case AccountId("anotherTestAccountId") => \/.right(List(digipack))
          case AccountId("manySubsPerAccount") => \/.right(List(contributor, digipack))
          case _ => \/.left(s"subscriptions not found for $testId")
        }
      }
    }

  }

  trait noAccounts extends Scope {
    val emptyQueryResponse = GetAccountsQueryResponse(records = Nil, size = 0)

    mockDynamoAttributesService.get(testId) returns Future.successful(None)

    def identityIdToAccountIds(identityId: String): Future[\/[String, GetAccountsQueryResponse]] = Future.successful(\/.right(emptyQueryResponse))
    def accountIdToAccountObject(accountId: AccountId) = Future.successful(\/.left("This account is not known so thus no summary"))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(Nil))
  }

  trait giftDigiSub extends Scope {
    mockDynamoAttributesService.get(testId) returns Future.successful(None)

    def identityIdToAccountIds(identityId: String): Future[\/[String, GetAccountsQueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(List(digipackGift)))
  }

  trait accountButNoSubscriptions extends Scope {
    mockDynamoAttributesService.get(testId) returns Future.successful(None)

    def identityIdToAccountIds(identityId: String): Future[\/[String, GetAccountsQueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))
    def accountIdToAccountObject(accountId: AccountId) = Future.successful(\/.right(accountObjectWithZeroBalance))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(Nil))
  }

  trait errorWhenGettingSubs extends Scope {
    val testErrorMessage = "Something bad happened! D:"

    def identityIdToAccountIds(identityId: String): Future[\/[String, GetAccountsQueryResponse]] = Future.successful(\/.right(oneAccountQueryResponse))
    def accountIdToAccountObject(accountId: AccountId) = Future.successful(\/.right(accountObjectWithZeroBalance))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.left(testErrorMessage))
  }

  trait errorWhenGettingAccounts extends Scope {
    val testErrorMessage = "Something bad happened during the zuora query! D:"

    mockDynamoAttributesService.get(testId) returns Future.successful(Some(supporterDynamoAttributes))

    def identityIdToAccountIds(identityId: String): Future[\/[String, GetAccountsQueryResponse]] = Future.successful(\/.left(testErrorMessage))
    def accountIdToAccountObject(accountId: AccountId) = Future.successful(\/.right(accountObjectWithZeroBalance))
    def subscriptionFromAccountId(accountId: AccountId)(reads: SubPlanReads[AnyPlan]) = Future.successful(\/.right(Nil))
  }

}
