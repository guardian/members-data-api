package services

import models.Attributes
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.amazonaws.services.dynamodbv2.model.{DeleteItemResult, PutItemResult}
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future
import com.gu.scanamo.syntax._
import com.gu.scanamo._
import com.typesafe.scalalogging.LazyLogging

class ScanamoAttributeService(client: AmazonDynamoDBAsyncClient, table: String)
    extends AttributeService with LazyLogging {

  private val scanamo = Table[Attributes](table)
  def run[T] = ScanamoAsync.exec[T](client) _


  override def get(userId: String): Future[Option[Attributes]] =
    run(scanamo.get('UserId -> userId).map(_.flatMap {
      _
        .left.map(e => logger.warn("Scanamo error in get: ", e))
        .right.toOption
    }))

  def getMany(userIds: List[String]): Future[Seq[Attributes]] =
    run(scanamo.getAll('UserId -> userIds.toSet)).map(_.flatMap{
      _
        .left.map(e => logger.warn("Scanamo error in getAll: ", e))
        .right.toOption
    }).map(_.toList)

  override def set(attributes: Attributes): Future[PutItemResult] = run(scanamo.put(attributes))

  override def delete(userId: String): Future[DeleteItemResult] = run(scanamo.delete('UserId -> userId))
}
