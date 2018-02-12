package repositories

import java.util.UUID

import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder
import com.amazonaws.services.dynamodbv2.model._
import models.{Attributes, DynamoAttributes}
import org.joda.time.{DateTime, LocalDate}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import services.ScanamoAttributeService

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


/**
  * Depends upon DynamoDB Local to be running on the default port of 8000.
  *
  * Amazon's embedded version doesn't work with an async client, so using https://github.com/localytics/sbt-dynamodb
  */
class ScanamoAttributeServiceTest(implicit ee: ExecutionEnv) extends Specification {

  object AttributeNames {
    val userId = "UserId"
    val membershipNumber = "MembershipNumber"
    val tier = "Tier"
    val membershipJoinDate = "MembershipJoinDate"
  }

  private val endpoint = new AwsClientBuilder.EndpointConfiguration("http://localhost:8000/", "eu-west-1")
  private val awsDynamoClient = AmazonDynamoDBAsyncClientBuilder
    .standard()
    .withEndpointConfiguration(endpoint) // .withCredentials(new BasicAWSCredentials("foo", "bar"))
    .build()

  private val testTable = "MembershipAttributes-TEST"

  private val repo = new ScanamoAttributeService(awsDynamoClient, testTable)

  val provisionedThroughput = new ProvisionedThroughput().withReadCapacityUnits(10L).withWriteCapacityUnits(5L)
  val userIdAtt = new AttributeDefinition().withAttributeName(AttributeNames.userId).withAttributeType(ScalarAttributeType.S)
  val keySchema = new KeySchemaElement().withAttributeName(AttributeNames.userId).withKeyType(KeyType.HASH)
  val tableRequest =
    new CreateTableRequest()
      .withTableName(testTable)
      .withProvisionedThroughput(provisionedThroughput)
      .withAttributeDefinitions(userIdAtt)
      .withKeySchema(keySchema)

  val createTableResult = awsDynamoClient.createTable(tableRequest)

  val testExpiryDate = DateTime.now()

  def toDynamoTtl(date: DateTime) = date.getMillis / 1000

  val testTtl = toDynamoTtl(testExpiryDate)

  "get" should {
    "retrieve attributes for given user" in {
      val userId = UUID.randomUUID().toString
      val attributes = DynamoAttributes(
        UserId = userId,
        Tier = Some("Patron"),
        MembershipNumber = Some("abc"),
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = Some(new LocalDate(2017, 6, 13)),
        TTLTimestamp = toDynamoTtl(testExpiryDate),
        DigitalSubscriptionExpiryDate = None,
        AdFree = None
      )


      val result = for {
        insertResult <- repo.set(attributes)
        retrieved <- repo.get(userId)
      } yield retrieved

      result must be_==(Some(attributes)).await
    }

    "retrieve not found api error when attributes not found for user" in {
      val result = for {
        retrieved <- repo.get(UUID.randomUUID().toString)
      } yield retrieved

      result must be_==(None).await
    }
  }

  "getMany" should {

    val testUsers = Seq(
      DynamoAttributes(UserId = "1234", Tier = Some("Partner"), TTLTimestamp = testTtl, MembershipNumber = None, AdFree = None),
      DynamoAttributes(UserId = "2345", Tier = Some("Partner"), MembershipJoinDate = Some(new LocalDate(2017, 6, 12)), TTLTimestamp = testTtl, MembershipNumber = None, AdFree = None),
      DynamoAttributes(UserId = "3456", Tier = Some("Partner"), MembershipJoinDate = Some(new LocalDate(2017, 6, 11)), TTLTimestamp = testTtl, MembershipNumber = None, AdFree = None),
      DynamoAttributes(UserId = "4567", Tier = Some("Partner"), MembershipJoinDate = Some(new LocalDate(2017, 6, 10)), TTLTimestamp = testTtl, MembershipNumber = None, AdFree = None)
    )

    "Fetch many people by user id" in {
      Await.result(Future.sequence(testUsers.map(repo.set)), 5.seconds)
      Await.result(repo.getMany(List("1234", "3456", "abcd")), 5.seconds) mustEqual Seq(
        DynamoAttributes(UserId = "1234", Tier = Some("Partner"), TTLTimestamp = testTtl, MembershipNumber = None, AdFree = None),
        DynamoAttributes(UserId = "3456", Tier = Some("Partner"), MembershipJoinDate = Some(new LocalDate(2017, 6, 11)), TTLTimestamp = testTtl, MembershipNumber = None, AdFree = None)
      )
    }
  }

  "update" should {
    "add the attribute if it's not already in the table" in {

      val newAttributes = DynamoAttributes(UserId = "6789", RecurringContributionPaymentPlan = Some("Monthly Contribution"), TTLTimestamp = testTtl, MembershipNumber = None, AdFree = None)

      val result = for {
        _ <- repo.update(newAttributes)
        retrieved <- repo.get("6789")
      } yield retrieved

      result must be_==(Some(newAttributes)).await

    }

    "update a user who has bought a digital subscription" in {
      val oldAttributes = DynamoAttributes(UserId = "6789", RecurringContributionPaymentPlan = Some("Monthly Contribution"), TTLTimestamp = testTtl, MembershipNumber = None, AdFree = None)
      val newAttributes = DynamoAttributes(UserId = "6789", RecurringContributionPaymentPlan = Some("Monthly Contribution"), DigitalSubscriptionExpiryDate = Some(LocalDate.now().plusWeeks(5)), TTLTimestamp = testTtl, MembershipNumber = None, AdFree = None)

      val result = for {
        _ <- repo.set(oldAttributes)
        _ <- repo.update(newAttributes)
        retrieved <- repo.get("6789")
      } yield retrieved

      result must be_==(Some(newAttributes)).await
    }

    "leave attribute in the table if nothing has changed" in {
      val existingAttributes = DynamoAttributes(UserId = "6789", AdFree = Some(true), DigitalSubscriptionExpiryDate = Some(LocalDate.now().plusWeeks(5)), TTLTimestamp = testTtl, MembershipNumber = None)

      val result = for {
        _ <- repo.set(existingAttributes)
        _ <- repo.update(existingAttributes)
        retrieved <- repo.get("6789")
      } yield retrieved

      result must be_==(Some(existingAttributes)).await
    }

    "leave existing values in an attribute that cannot be determined from a zuora update alone" in {
      val existingAttributes = DynamoAttributes(UserId = "6789", AdFree = Some(true), DigitalSubscriptionExpiryDate = Some(LocalDate.now().minusWeeks(5)), MembershipNumber = Some("1234"), TTLTimestamp = testTtl)
      val updatedAttributes = DynamoAttributes(UserId = "6789", DigitalSubscriptionExpiryDate = Some(LocalDate.now().plusWeeks(5)), TTLTimestamp = testTtl, MembershipNumber = None, AdFree = None)
      val attributesWithPreservedValues = existingAttributes.copy(DigitalSubscriptionExpiryDate = updatedAttributes.DigitalSubscriptionExpiryDate) //TTL is also only in dynamo, but the logic for it is in attributesFromZuora.

      val result = for {
        _ <- repo.set(existingAttributes)
        _ <- repo.update(updatedAttributes)
        retrieved <- repo.get("6789")
      } yield retrieved

      result must be_==(Some(attributesWithPreservedValues)).await
    }

  }
}


