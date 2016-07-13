package services

import com.amazonaws.services.dynamodbv2.model.{DeleteItemResult, PutItemResult}
import models.Attributes
import scala.concurrent.Future

trait AttributeService {
  def get(userId: String): Future[Option[Attributes]]
  def getMany(userIds: List[String]): Future[Seq[Attributes]]
  def delete(userId: String): Future[DeleteItemResult]
  def set(attributes: Attributes): Future[PutItemResult]
}
