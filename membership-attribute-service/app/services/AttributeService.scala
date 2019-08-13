package services

import com.amazonaws.services.dynamodbv2.model.{DeleteItemResult, PutItemResult}
import com.gu.scanamo.error.DynamoReadError
import models.DynamoAttributes
import org.joda.time.LocalDate

import scala.concurrent.Future

trait AttributeService extends HealthCheckableService {
  def get(userId: String): Future[Option[DynamoAttributes]]
  def getMany(userIds: List[String]): Future[Seq[DynamoAttributes]]
  def delete(userId: String): Future[DeleteItemResult]
  def set(attributes: DynamoAttributes): Future[Option[Either[DynamoReadError, DynamoAttributes]]]
  def update(attributes: DynamoAttributes): Future[Either[DynamoReadError, DynamoAttributes]]
  def ttlAgeCheck(dynamoAttributes: Option[DynamoAttributes], identityId: String, currentDate: LocalDate): Unit
}
