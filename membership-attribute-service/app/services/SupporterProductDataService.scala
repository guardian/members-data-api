package services

import cats.data.EitherT
import com.typesafe.scalalogging.LazyLogging
import models.{Attributes, DynamoSupporterRatePlanItem}
import monitoring.Metrics
import org.joda.time.LocalDate
import org.scanamo.DynamoReadError.describe
import org.scanamo.{DynamoReadError, _}
import org.scanamo.generic.semiauto._
import org.scanamo.syntax._
import services.SupporterProductDataService.{alertOnDynamoReadErrors, errorMessage}
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

import scala.concurrent.{ExecutionContext, Future}

class SupporterProductDataService(client: DynamoDbAsyncClient, table: String, mapper: SupporterRatePlanToAttributesMapper)(implicit
    executionContext: ExecutionContext,
) {

  implicit val jodaStringFormat: DynamoFormat[LocalDate] =
    DynamoFormat.coercedXmap[LocalDate, String, IllegalArgumentException](LocalDate.parse, _.toString)
  implicit val dynamoSupporterRatePlanItem: DynamoFormat[DynamoSupporterRatePlanItem] = deriveDynamoFormat

  def getAttributes(identityId: String): Future[Either[String, Option[Attributes]]] =
    getSupporterRatePlanItems(identityId).map(ratePlanItems => mapper.attributesFromSupporterRatePlans(identityId, ratePlanItems)).value

  def getSupporterRatePlanItems(identityId: String) = {
    EitherT(
      for {
        futureDynamoResult <- getSupporterRatePlanItemsWithReadErrors(identityId)
        futureErrors = futureDynamoResult.collect { case Left(error) =>
          error
        }
        _ = alertOnDynamoReadErrors(futureErrors)
        futureRatePlanItems = futureDynamoResult.collect({ case Right(ratePlanItem) =>
          ratePlanItem
        })
      } yield
        if (futureErrors.isEmpty || futureRatePlanItems.nonEmpty)
          Right(futureRatePlanItems)
        else
          Left(errorMessage(futureErrors)),
    )
  }

  private def getSupporterRatePlanItemsWithReadErrors(identityId: String) =
    ScanamoAsync(client).exec {
      Table[DynamoSupporterRatePlanItem](table)
        .query("identityId" === identityId)
    }

}

object SupporterProductDataService extends LazyLogging {
  val metrics = Metrics("SupporterProductDataService") // referenced in CloudFormation

  def errorMessage(errors: List[DynamoReadError]) =
    s"There were read errors while reading from the SupporterProductData DynamoDB table\n ${errors.map(describe).mkString("\n")}"

  def alertOnDynamoReadErrors(errors: List[DynamoReadError]) =
    if (errors.nonEmpty) {
      logger.error(errorMessage(errors))
      metrics.put("SupporterProductDataDynamoError", 1) // referenced in CloudFormation
    }
}
