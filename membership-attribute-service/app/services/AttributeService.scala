package services

import models.DynamoAttributes
import org.joda.time.LocalDate
import org.scanamo.DynamoReadError

import scala.concurrent.Future

trait AttributeService extends HealthCheckableService {
  def get(userId: String): Future[Option[DynamoAttributes]]
  def getMany(userIds: List[String]): Future[Seq[DynamoAttributes]]
  def delete(userId: String): Future[Unit]
  def set(attributes: DynamoAttributes): Future[Unit]
  def update(attributes: DynamoAttributes): Future[Either[DynamoReadError, DynamoAttributes]]
  def ttlAgeCheck(dynamoAttributes: Option[DynamoAttributes], identityId: String, currentDate: LocalDate): Unit
}
