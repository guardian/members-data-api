package repositories

import javax.inject.Inject

import com.github.dwhjames.awswrap.dynamodb._
import com.github.dwhjames.awswrap.dynamodb.AmazonDynamoDBScalaMapper
import models._
import play.api.Logger

import scala.concurrent.ExecutionContext.Implicits.global
import MembershipAttributesDynamo.membershipAttributesSerializer

class MembershipAttributesRepository @Inject() (dynamo: AmazonDynamoDBScalaMapper) {

  private val logger = Logger(this.getClass)

  lazy val NotFoundError = ApiErrors(List(ApiError("Not Found", "No membership attributes found for user", 404)))

  val handleError: PartialFunction[Throwable, Either[ApiErrors, MembershipAttributes]] = {
    case t: Throwable =>
      logger.error("Unexpected error", t)
      scala.Left(ApiErrors(List(ApiError.unexpected(t.getMessage))))
  }

  def getAttributes(userId: String): ApiResponse[MembershipAttributes] = {
    logger.debug(s"Get attributes for userId: $userId")
    // TODO handle the error case
    val result = dynamo.loadByKey[MembershipAttributes](userId).map(_.toRight(NotFoundError))

    ApiResponse.Async(result, handleError)
  }

  def updateAttributes(attributes: MembershipAttributes): ApiResponse[MembershipAttributes] = {
    logger.debug(s"Update attributes: $attributes")
    // TODO handle the error case
    val result = dynamo.dump(attributes).map(_ =>
      Right(attributes)
    )

    ApiResponse.Async(result, handleError)
  }
}
