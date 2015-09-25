package repositories

import java.util.UUID

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest
import com.github.dwhjames.awswrap.dynamodb.{AmazonDynamoDBScalaClient, AmazonDynamoDBScalaMapper, Schema}
import models.MembershipAttributes
import org.specs2.mutable.Specification
import repositories.MembershipAttributesDynamo.Attributes

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
 * Depends upon DynamoDB Local to be running on the default port of 8000.
 *
 * Amazon's embedded version doesn't work with an async client, so using https://github.com/grahamar/sbt-dynamodb
 */
class MembershipAttributesRepositoryTest extends Specification {

  val awsDynamoClient = new AmazonDynamoDBAsyncClient(new BasicAWSCredentials("foo", "bar"))
  awsDynamoClient.setEndpoint("http://localhost:8000")
  val dynamoClient = new AmazonDynamoDBScalaClient(awsDynamoClient)
  val dynamoMapper = AmazonDynamoDBScalaMapper(dynamoClient)
  val repo = new MembershipAttributesRepository(dynamoMapper)

  val tableRequest =
    new CreateTableRequest()
      .withTableName(MembershipAttributesDynamo.tableName)
      .withProvisionedThroughput(
        Schema.provisionedThroughput(10L, 5L))
      .withAttributeDefinitions(
        Schema.stringAttribute(Attributes.userId))
      .withKeySchema(
        Schema.hashKey(Attributes.userId))

  val createTableResult = Await.result(dynamoClient.createTable(tableRequest), 5.seconds)

  "getAttributes" should {
    "retrieve attributes for given user" in {
      val userId = UUID.randomUUID().toString
      val attributes = MembershipAttributes(userId, "patron", Some("abc"))
      val result = for {
        insertResult <- repo.updateAttributes(attributes)
        retrieved <- repo.getAttributes(userId)
      } yield retrieved

      Await.result(result, 5.seconds) shouldEqual Some(attributes)
    }

    "retrieve not found api error when attributes not found for user" in {
      val result = for {
        retrieved <- repo.getAttributes(UUID.randomUUID().toString)
      } yield retrieved

      Await.result(result, 5.seconds) shouldEqual None
    }
  }

}
