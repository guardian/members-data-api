package services

import com.github.dwhjames.awswrap.dynamodb._
import configuration.Config
import models._
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._

import scala.Function.const
import scala.concurrent.Future

object DynamoAttributeService {
  def apply(implicit serializer: DynamoDBSerializer[Attributes]): DynamoAttributeService = DynamoAttributeService(Config.dynamoMapper)
}

case class DynamoAttributeService(mapper: AmazonDynamoDBScalaMapper)(implicit serializer: DynamoDBSerializer[Attributes]) extends AttributeService {
  private val logger = Logger(this.getClass)

  implicit class FutureLogging[T](f: Future[T]) {
    def withErrorLogging(message: String) = {
      f.onFailure { case t => Logger.error(message, t) }
      f
    }
  }

  def get(userId: String): Future[Option[Attributes]] = {
    logger.debug(s"Get attributes for userId: $userId")
    mapper.loadByKey[Attributes](userId)
      .withErrorLogging(s"Failed to get attributes for userId: $userId")
  }

  def delete(userId: String): Future[Unit] = {
    logger.debug(s"Delete user id: $userId")
    mapper.deleteByKey(userId).map(const(()))
      .withErrorLogging(s"Failed to delete for userId: $userId")
  }

  def set(attributes: Attributes): Future[Unit] = {
    logger.debug(s"Update attributes: $attributes")
    mapper.dump(attributes)
      .withErrorLogging(s"Failed to update attributes $attributes")
  }
}
