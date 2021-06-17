package repositories

import models.DynamoAttributes
import org.joda.time.{DateTime, LocalDate}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import services.ScanamoAttributeService
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model._

import java.net.URI
import java.util.UUID
import scala.compat.java8.FutureConverters
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

  private val awsDynamoClient = DynamoDbAsyncClient.builder
    .endpointOverride(URI.create("http://localhost:8000/"))
    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar")))
    .region(Region.EU_WEST_1)
    .build

  private val testTable = "MembershipAttributes-TEST"

  private val repo = new ScanamoAttributeService(awsDynamoClient, testTable)

  val provisionedThroughput =  ProvisionedThroughput.builder().readCapacityUnits(10L).writeCapacityUnits(5L).build()
  val userIdAtt = AttributeDefinition.builder().attributeName(AttributeNames.userId).attributeType(ScalarAttributeType.S).build()
  val keySchema = KeySchemaElement.builder().attributeName(AttributeNames.userId).keyType(KeyType.HASH).build()
  val tableRequest =
    CreateTableRequest.builder()
      .tableName(testTable)
      .provisionedThroughput(provisionedThroughput)
      .attributeDefinitions(userIdAtt)
      .keySchema(keySchema)
      .build()

  val createTableResult = Await.result(FutureConverters.toScala(awsDynamoClient.createTable(tableRequest)), 20.seconds)

  val testExpiryDate = DateTime.now()

  def toDynamoTtl(date: DateTime) = date.getMillis / 1000

  val testTtl = toDynamoTtl(testExpiryDate)

  "get" should {
    "retrieve attributes for given user" in {
      val userId = UUID.randomUUID().toString
      val attributes = DynamoAttributes(
        UserId = userId,
        Tier = Some("Patron"),
        RecurringContributionPaymentPlan = Some("Monthly Contribution"),
        MembershipJoinDate = Some(new LocalDate(2017, 6, 13)),
        TTLTimestamp = toDynamoTtl(testExpiryDate),
        DigitalSubscriptionExpiryDate = None
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
      DynamoAttributes(UserId = "1234", Tier = Some("Partner"), TTLTimestamp = testTtl),
      DynamoAttributes(UserId = "2345", Tier = Some("Partner"), MembershipJoinDate = Some(new LocalDate(2017, 6, 12)), TTLTimestamp = testTtl),
      DynamoAttributes(UserId = "3456", Tier = Some("Partner"), MembershipJoinDate = Some(new LocalDate(2017, 6, 11)), TTLTimestamp = testTtl),
      DynamoAttributes(UserId = "4567", Tier = Some("Partner"), MembershipJoinDate = Some(new LocalDate(2017, 6, 10)), TTLTimestamp = testTtl)
    )

    "Fetch many people by user id" in {
      Await.result(Future.sequence(testUsers.map(repo.set)), 5.seconds)
      Await.result(repo.getMany(List("1234", "3456", "abcd")), 5.seconds) mustEqual Seq(
        DynamoAttributes(UserId = "1234", Tier = Some("Partner"), TTLTimestamp = testTtl),
        DynamoAttributes(UserId = "3456", Tier = Some("Partner"), MembershipJoinDate = Some(new LocalDate(2017, 6, 11)), TTLTimestamp = testTtl)
      )
    }
  }

  "update" should {
    "add the attribute if it's not already in the table" in {

      val newAttributes = DynamoAttributes(UserId = "6789", RecurringContributionPaymentPlan = Some("Monthly Contribution"), TTLTimestamp = testTtl)

      val result = for {
        _ <- repo.update(newAttributes)
        retrieved <- repo.get("6789")
      } yield retrieved

      result must beSome(newAttributes).await

    }

    "update a user who has bought a digital subscription" in {
      val oldAttributes = DynamoAttributes(UserId = "6789", RecurringContributionPaymentPlan = Some("Monthly Contribution"), TTLTimestamp = testTtl)
      val newAttributes = DynamoAttributes(UserId = "6789", RecurringContributionPaymentPlan = Some("Monthly Contribution"), DigitalSubscriptionExpiryDate = Some(LocalDate.now().plusWeeks(5)), TTLTimestamp = testTtl)

      val result = for {
        _ <- repo.set(oldAttributes)
        _ <- repo.update(newAttributes)
        retrieved <- repo.get("6789")
      } yield retrieved

      result must be_==(Some(newAttributes)).await
    }

    "leave attribute in the table if nothing has changed" in {
      val existingAttributes = DynamoAttributes(UserId = "6789", DigitalSubscriptionExpiryDate = Some(LocalDate.now().plusWeeks(5)), TTLTimestamp = testTtl)

      val result = for {
        _ <- repo.set(existingAttributes)
        _ <- repo.update(existingAttributes)
        retrieved <- repo.get("6789")
      } yield retrieved

      result must be_==(Some(existingAttributes)).await
    }

  }
}


