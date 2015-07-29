package repositories

import java.util.UUID

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.github.dwhjames.awswrap.dynamodb.{AmazonDynamoDBScalaClient, AmazonDynamoDBScalaMapper}
import models.MembershipAttributes
import org.joda.time.LocalDate
import org.specs2.mutable.Specification

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
  val dynamoClient    = new AmazonDynamoDBScalaClient(awsDynamoClient)
  val dynamoMapper = AmazonDynamoDBScalaMapper(dynamoClient)
  val repo = new MembershipAttributesRepository(dynamoMapper)
  val createTableResult = Await.result(dynamoClient.createTable(MembershipAttributesDynamo.tableRequest), 5.seconds)

  "getAttributes" should {
    "retrieve attributes for given user" in {
      val attributes = MembershipAttributes(UUID.randomUUID().toString, LocalDate.parse("2015-07-28"), "patron", "abc")
      val result = for {
        insertResult <- repo.updateAttributes(attributes)
        retrieved <- repo.getAttributes(attributes.userId)
      } yield retrieved

      Await.result(result.asFuture, 5.seconds) shouldEqual scala.Right(attributes)
    }

    "retrieve not found api error when attributes not found for user" in {
      val attributes = MembershipAttributes(UUID.randomUUID().toString, LocalDate.parse("2015-07-28"), "patron", "abc")
      val result = for {
        retrieved <- repo.getAttributes(attributes.userId)
      } yield retrieved

      Await.result(result.asFuture, 5.seconds) shouldEqual scala.Left(repo.NotFoundError)
    }
  }

}
