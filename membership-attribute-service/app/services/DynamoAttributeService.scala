package services

import com.github.dwhjames.awswrap.dynamodb._
import configuration.Config
import models._
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._

import scala.Function.const
import scala.concurrent.Future

object DynamoAttributeService {
  def apply(implicit serializer: DynamoDBSerializer[MembershipAttributes]): DynamoAttributeService = DynamoAttributeService(Config.dynamoMapper)
}

case class DynamoAttributeService(mapper: AmazonDynamoDBScalaMapper)(implicit serializer: DynamoDBSerializer[MembershipAttributes]) extends AttributeService {
  private val logger = Logger(this.getClass)

  implicit class FutureLogging[T](f: Future[T]) {
    def withErrorLogging(message: String) = {
      f.onFailure { case t => Logger.error(message, t) }
      f
    }
  }

  def get(userId: String): Future[Option[MembershipAttributes]] = {
    logger.debug(s"Get attributes for userId: $userId")
    mapper.loadByKey[MembershipAttributes](userId)
      .withErrorLogging(s"Failed to get attributes for userId: $userId")
  }

  def delete(userId: String): Future[Unit] = {
    logger.debug(s"Delete user id: $userId")
    mapper.deleteByKey(userId).map(const(()))
      .withErrorLogging(s"Failed to delete for userId: $userId")
  }

  def set(attributes: MembershipAttributes): Future[Unit] = {
    logger.debug(s"Update attributes: $attributes")
    mapper.dump(attributes)
      .withErrorLogging(s"Failed to update attributes $attributes")
  }
}
