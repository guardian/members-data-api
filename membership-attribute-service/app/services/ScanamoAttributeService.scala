package services
import models.Attributes
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future
import com.gu.scanamo.syntax._
import com.gu.scanamo._

class ScanamoAttributeService(client: AmazonDynamoDBAsyncClient, table: String) extends AttributeService {

  val scanamo = Table[Attributes](table)
  def run[T] = ScanamoAsync.exec[T](client) _

  override def get(userId: String): Future[Option[Attributes]] =
    run(scanamo.get('UserId -> userId).map(_.flatMap(_.toOption)))

  def getMany(userIds: List[String]): Future[Seq[Attributes]] =
    run(scanamo.getAll('UserId -> userIds.toSet.toList)).map(_.flatMap(_.toOption))

  override def set(attributes: Attributes): Future[Unit] =
    run(scanamo.put(attributes)).map(_ => Unit)

  override def delete(userId: String): Future[Unit] =
    run(scanamo.delete('UserId -> userId)).map(_ => Unit)
}
