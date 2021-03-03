package services

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.gu.scanamo.syntax._
import com.gu.scanamo.{ScanamoAsync, Table, _}
import com.typesafe.scalalogging.LazyLogging
import models.SupporterRatePlanItem
import org.joda.time.{Instant, LocalDate}

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
      _ = futureDynamoResult.collect { case Left(error) =>
        error
      }.foreach(error => logger.error(error.toString))
      futureRatePlanItems = futureDynamoResult.collect({ case Right(ratePlanItem) =>
        ratePlanItem
      })
    } yield mapper.attributesFromSupporterRatePlans(identityId, futureRatePlanItems)

  private def getSupporterRatePlanItems(identityId: String) =
    ScanamoAsync.exec(client) {
      Table[SupporterRatePlanItem](table)
        .query('identityId -> identityId)

    }

}
