package services

import com.amazonaws.services.dynamodbv2.model.{DeleteItemResult, PutItemResult}
import models.Behaviour

import scala.concurrent.Future

trait BehaviourService {
  def get(userId: String): Future[Option[Behaviour]]
  def set(behaviour: Behaviour): Future[PutItemResult]
  def delete(userId: String): Future[DeleteItemResult]
}
