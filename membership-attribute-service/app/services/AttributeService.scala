package services

import com.amazonaws.services.dynamodbv2.model.{DeleteItemResult, PutItemResult}
import com.gu.scanamo.error.DynamoReadError
import models.Attributes
import org.joda.time.LocalDate

import scala.concurrent.Future

trait AttributeService extends HealthCheckableService {
  def get(userId: String): Future[Option[Attributes]]
  def getMany(userIds: List[String]): Future[Seq[Attributes]]
  def delete(userId: String): Future[DeleteItemResult]
  def set(attributes: Attributes): Future[PutItemResult]
  def update(attributes: Attributes): Future[Either[DynamoReadError, Attributes]]
  def ttlAgeCheck(dynamoAttributes: Option[Attributes], identityId: String, currentDate: LocalDate): Unit
}
