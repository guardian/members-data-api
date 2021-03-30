package services

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.error.DynamoReadError.describe
import com.gu.scanamo.syntax._
import com.gu.scanamo._
import com.typesafe.scalalogging.LazyLogging
import models.DynamoSupporterRatePlanItem
import monitoring.Metrics
import org.joda.time.{Instant, LocalDate}
import scalaz.\/
import services.SupporterProductDataService.{alertOnDynamoReadErrors, errorMessage}

import scala.concurrent.ExecutionContext

class SupporterProductDataService(client: AmazonDynamoDBAsync, table: String, mapper: SupporterRatePlanToAttributesMapper)
  (implicit executionContext: ExecutionContext) {

  implicit val jodaStringFormat: DynamoFormat[LocalDate] = DynamoFormat.coercedXmap[LocalDate, String, IllegalArgumentException](LocalDate.parse)(
    _.toString
  )

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
    ScanamoAsync.exec(client) {
      Table[DynamoSupporterRatePlanItem](table)
        .query('identityId -> identityId)

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
