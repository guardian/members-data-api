package repositories

import javax.inject.Inject

import models.{ApiError, ApiErrors, ApiResponse, MembershipAttributes}
import com.github.dwhjames.awswrap.dynamodb.AmazonDynamoDBScalaMapper
import play.api.Logger
import com.github.dwhjames.awswrap.dynamodb._

import scala.concurrent.ExecutionContext.Implicits.global

class MembershipAttributesRepository @Inject() (dynamo: AmazonDynamoDBScalaMapper) {

  private val logger = Logger(this.getClass)

  lazy val NotFoundError = ApiErrors(List(ApiError("Not Found", "No membership attributes found for user", 404)))

  def getAttributes(userId: String): ApiResponse[MembershipAttributes] = {
    logger.debug(s"Get attributes for userId: $userId")
    val result = dynamo.loadByKey[MembershipAttributesDynamo](userId).map(x =>
      x.map(_.toMembershipAttributes).toRight(NotFoundError)
    )

    ApiResponse.Async(result)
  }

  def updateAttributes(attributes: MembershipAttributes): ApiResponse[MembershipAttributes] = {
    logger.debug(s"Update attributes: $attributes")
    val result = dynamo.dump(MembershipAttributesDynamo(attributes)).map(x =>
      scala.Right(attributes)
    )

    ApiResponse.Async(result)
  }
}
