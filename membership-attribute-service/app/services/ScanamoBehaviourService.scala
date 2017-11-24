package services

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.model.{DeleteItemResult, PutItemResult}
import com.gu.scanamo.syntax._
import com.gu.scanamo.{ScanamoAsync, Table}
import com.typesafe.scalalogging.LazyLogging
import models.Behaviour
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future

class ScanamoBehaviourService(client: AmazonDynamoDBAsync, table: String) extends BehaviourService with LazyLogging {

  def checkHealth: Boolean = client.describeTable(table).getTable.getTableStatus == "ACTIVE"

  val scanamo = Table[Behaviour](table)
  def run[T] = ScanamoAsync.exec[T](client) _

  override def get(userId: String): Future[Option[Behaviour]] =
    run(scanamo.get('userId -> userId).map(_.flatMap {
      _
        .left.map(e => logger.warn("Scanamo error in get: ", e))
        .right.toOption
    }))
  
  override def set(behaviour: Behaviour): Future[PutItemResult] = run(scanamo.put(behaviour))

  override def delete(userId: String): Future[DeleteItemResult] = run(scanamo.delete('userId -> userId))
}
