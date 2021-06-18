package services

import com.typesafe.scalalogging.LazyLogging
import models.FeatureToggle
import org.scanamo.{DynamoReadError, _}
import org.scanamo.generic.semiauto._
import org.scanamo.syntax._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.{DescribeTableRequest, TableStatus}

import scala.concurrent.{ExecutionContext, Future}

class ScanamoFeatureToggleService(
  client: DynamoDbAsyncClient,
  table: String
)(implicit executionContext: ExecutionContext) extends HealthCheckableService with LazyLogging {

  def checkHealth: Boolean = client.describeTable(DescribeTableRequest.builder().tableName(table).build()).get.table().tableStatus() == TableStatus.ACTIVE

  implicit val dynamoFeatureToggle: DynamoFormat[FeatureToggle] = deriveDynamoFormat

  def get(featureName: String): Future[Either[String, FeatureToggle]] =
    ScanamoAsync(client).exec {
      Table[FeatureToggle](table)
        .get("FeatureName" === featureName)
        .map {
          case Some(value) => value.left.map(DynamoReadError.describe)
          case None => Left("Feature toggle not found")
        }
    }
}
