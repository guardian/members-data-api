package services

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.gu.scanamo.error.DynamoReadError.describe
import com.gu.scanamo.syntax._
import com.gu.scanamo.{ScanamoAsync, Table, _}
import com.typesafe.scalalogging.LazyLogging
import models.DynamoSupporterRatePlanItem
import org.joda.time.{Instant, LocalDate}
import scalaz.\/

import scala.concurrent.ExecutionContext

class SupporterProductDataService(client: AmazonDynamoDBAsync, table: String, mapper: SupporterRatePlanToAttributesMapper)
  (implicit executionContext: ExecutionContext) extends LazyLogging {

  implicit val jodaNumberFormat: DynamoFormat[LocalDate] =
    DynamoFormat.coercedXmap[LocalDate, Long, IllegalArgumentException](epochSeconds => Instant.ofEpochSecond(epochSeconds).toDateTime.toLocalDate)(
      // We need epoch time in seconds https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/time-to-live-ttl-before-you-start.html
      _.toDateTimeAtStartOfDay.getMillis / 1000
    )

  def getAttributes(identityId: String) =
    for {
      futureDynamoResult <- getSupporterRatePlanItems(identityId)
      futureErrors = futureDynamoResult.collect { case Left(error) =>
        error
      }
      _ = futureErrors.foreach(error => logger.error(error.toString))
      futureRatePlanItems = futureDynamoResult.collect({ case Right(ratePlanItem) =>
        ratePlanItem
      })
    } yield if(futureErrors.isEmpty || futureRatePlanItems.nonEmpty)
      \/.right(mapper.attributesFromSupporterRatePlans(identityId, futureRatePlanItems))
    else
      \/.left(s"Errors occurred while fetching supporter-product-data from DynamoDB: ${futureErrors.map(describe).mkString(", ")}")

  private def getSupporterRatePlanItems(identityId: String) =
    ScanamoAsync.exec(client) {
      Table[DynamoSupporterRatePlanItem](table)
        .query('identityId -> identityId)

    }

}
