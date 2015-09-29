package services

import com.github.dwhjames.awswrap.dynamodb._
import configuration.Config
import models._
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._
import repositories.MembershipAttributesDynamo.membershipAttributesSerializer

import scala.Function.const
import scala.concurrent.Future

object DynamoAttributeService {
  def apply(): DynamoAttributeService = DynamoAttributeService(Config.dynamoMapper)
}

case class DynamoAttributeService(mapper: AmazonDynamoDBScalaMapper) extends AttributeService {
  private val logger = Logger(this.getClass)

  def get(userId: String): Future[Option[MembershipAttributes]] = {
    logger.debug(s"Get attributes for userId: $userId")
    mapper.loadByKey[MembershipAttributes](userId).recoverWith {
      case t =>
        Logger.error(s"Failed to get attributes for userId: $userId", t)
        Future.failed(t)
    }
  }

  def delete(userId: String): Future[Unit] = {
    logger.debug(s"Delete user id: $userId")
    mapper.deleteByKey(userId).map(const(())).recover {
      case t => Logger.error(s"Failed to delete for userId: $userId", t)
    }
  }

  def set(attributes: MembershipAttributes): Future[Unit] = {
    logger.debug(s"Update attributes: $attributes")
    mapper.dump(attributes).recover {
      case t => Logger.error(s"Failed to delete attributes $attributes", t)
    }
  }
}
