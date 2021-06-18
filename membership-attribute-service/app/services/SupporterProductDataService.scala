package services

import com.typesafe.scalalogging.LazyLogging
import models.DynamoSupporterRatePlanItem
import monitoring.Metrics
import org.joda.time.LocalDate
import org.scanamo.DynamoReadError.describe
import org.scanamo.{DynamoReadError, _}
import org.scanamo.generic.semiauto._
import org.scanamo.syntax._
import scalaz.\/
import services.SupporterProductDataService.{alertOnDynamoReadErrors, errorMessage}
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

import scala.concurrent.ExecutionContext

class SupporterProductDataService(client: DynamoDbAsyncClient, table: String, mapper: SupporterRatePlanToAttributesMapper)
  (implicit executionContext: ExecutionContext) {

  implicit val jodaStringFormat: DynamoFormat[LocalDate] = DynamoFormat.coercedXmap[LocalDate, String, IllegalArgumentException](LocalDate.parse,
    _.toString
  )
  implicit val dynamoSupporterRatePlanItem: DynamoFormat[DynamoSupporterRatePlanItem] = deriveDynamoFormat

  def getAttributes(identityId: String) =
    for {
      futureDynamoResult <- getSupporterRatePlanItems(identityId)
      futureErrors = futureDynamoResult.collect { case Left(error) =>
        error
      }
      _ = alertOnDynamoReadErrors(futureErrors)
      futureRatePlanItems = futureDynamoResult.collect({ case Right(ratePlanItem) =>
        ratePlanItem
      })
    } yield if(futureErrors.isEmpty || futureRatePlanItems.nonEmpty)
      \/.right(mapper.attributesFromSupporterRatePlans(identityId, futureRatePlanItems))
    else
      \/.left(errorMessage(futureErrors))

  private def getSupporterRatePlanItems(identityId: String) =
    ScanamoAsync(client).exec {
      Table[DynamoSupporterRatePlanItem](table)
        .query("identityId" === identityId)

    }

}

object SupporterProductDataService extends LazyLogging {
  val metrics = Metrics("SupporterProductDataService") //referenced in CloudFormation

  def errorMessage(errors: List[DynamoReadError]) =
    s"There were read errors while reading from the SupporterProductData DynamoDB table\n ${errors.map(describe).mkString("\n")}"

  def alertOnDynamoReadErrors(errors: List[DynamoReadError]) =
    if (errors.nonEmpty){
      logger.error(errorMessage(errors))
      metrics.put("SupporterProductDataDynamoError", 1) //referenced in CloudFormation
    }
}
