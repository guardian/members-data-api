package repositories

import javax.inject.Inject

import com.github.dwhjames.awswrap.dynamodb.{AmazonDynamoDBScalaMapper, _}
import models._
import play.api.Logger
import repositories.MembershipAttributesDynamo.membershipAttributesSerializer

import scala.concurrent.Future

class MembershipAttributesRepository @Inject() (dynamo: AmazonDynamoDBScalaMapper) {

  private val logger = Logger(this.getClass)

  def getAttributes(userId: String): Future[Option[MembershipAttributes]] = {
    logger.debug(s"Get attributes for userId: $userId")
    dynamo.loadByKey[MembershipAttributes](userId)
  }

  def updateAttributes(attributes: MembershipAttributes): Future[Unit] = {
    logger.debug(s"Update attributes: $attributes")
    dynamo.dump(attributes)
  }
}
