package services

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.gu.scanamo._
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.syntax.{set => scanamoSet, _}
import com.typesafe.scalalogging.LazyLogging
import models.FeatureToggle
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future

class ScanamoFeatureToggleService(client: AmazonDynamoDBAsyncClient, table: String) extends LazyLogging {

  private val scanamo = Table[FeatureToggle](table)
  def run[T] = ScanamoAsync.exec[T](client) _

  def get(featureName: String): Future[Option[FeatureToggle]] =
    run(scanamo.get('FeatureName -> featureName).map(_.flatMap {
      _
        .left.map(e => logger.warn(s"Scanamo error in get: ${DynamoReadError.describe(e)}", e))
        .right.toOption
    }))

}
