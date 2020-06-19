package services

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.gu.scanamo._
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.syntax.{set => scanamoSet, _}
import com.typesafe.scalalogging.LazyLogging
import models.FeatureToggle

import scala.concurrent.{ExecutionContext, Future}

class ScanamoFeatureToggleService(
  client: AmazonDynamoDBAsync,
  table: String
)(implicit executionContext: ExecutionContext) extends HealthCheckableService with LazyLogging {

  def checkHealth: Boolean = client.describeTable(table).getTable.getTableStatus == "ACTIVE"

  def get(featureName: String): Future[Either[String, FeatureToggle]] =
    ScanamoAsync.exec(client) {
      Table[FeatureToggle](table)
        .get('FeatureName -> featureName)
        .map {
          case Some(value) => value.left.map(DynamoReadError.describe)
          case None => Left("Feature toggle not found")
        }
    }
}
