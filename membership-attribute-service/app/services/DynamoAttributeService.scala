package services

import com.github.dwhjames.awswrap.dynamodb._
import configuration.Config
import models._
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._
import repositories.MembershipAttributesDynamo.membershipAttributesSerializer

import scala.Function.const
import scala.concurrent.Future

object DynamoAttributeService extends AttributeService {
  private val dynamo = Config.dynamoMapper
  private val logger = Logger(this.getClass)

  def get(userId: String): Future[Option[MembershipAttributes]] = {
    logger.debug(s"Get attributes for userId: $userId")
    dynamo.loadByKey[MembershipAttributes](userId)
  }

  def delete(userId: String): Future[Unit] = {
    logger.debug(s"Delete user id: $userId")
    dynamo.deleteByKey(userId).map(const(()))
  }

  def set(attributes: MembershipAttributes): Future[Unit] = {
    logger.debug(s"Update attributes: $attributes")
    dynamo.dump(attributes)
  }
}
