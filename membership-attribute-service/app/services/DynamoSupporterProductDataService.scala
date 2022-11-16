package services

import cats.data.EitherT
import com.gu.i18n.Currency
import com.typesafe.scalalogging.LazyLogging
import models.{Attributes, DynamoSupporterRatePlanItem}
import monitoring.Metrics
import org.joda.time.{DateTimeZone, LocalDate}
import org.scanamo.DynamoReadError.describe
import org.scanamo._
import org.scanamo.generic.semiauto._
import org.scanamo.syntax._
import services.DynamoSupporterProductDataService.{alertOnDynamoReadErrors, errorMessage}
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

import scala.concurrent.{ExecutionContext, Future}

trait SupporterProductDataService {
  def getAttributes(identityId: String): Future[Either[String, Option[Attributes]]]

  def getSupporterRatePlanItems(identityId: String): EitherT[Future, String, List[DynamoSupporterRatePlanItem]]
}

class DynamoSupporterProductDataService(client: DynamoDbAsyncClient, table: String, mapper: SupporterRatePlanToAttributesMapper)(implicit
                                                                                                                                 executionContext: ExecutionContext,
) extends SupporterProductDataService {

  implicit val jodaStringFormat: DynamoFormat[LocalDate] =
    DynamoFormat.coercedXmap[LocalDate, String, IllegalArgumentException](LocalDate.parse, _.toString)
  implicit val currencyFormat: DynamoFormat[Currency] =
    DynamoFormat.xmap[Currency, String](s => Currency.fromString(s).toRight(TypeCoercionError(new Throwable("Invalid currency"))), _.iso)
  implicit val dynamoSupporterRatePlanItem: DynamoFormat[DynamoSupporterRatePlanItem] = deriveDynamoFormat

  def getNonCancelledAttributes(identityId: String): Future[Either[String, Option[Attributes]]] = {
    getSupporterRatePlanItems(identityId).map { ratePlanItems =>
      val nonCancelled = ratePlanItems.filter(item => !item.cancellationDate.exists(_.isBefore(LocalDate.now(DateTimeZone.UTC))))
      mapper.attributesFromSupporterRatePlans(identityId, nonCancelled)
    }.value
  }

  def getSupporterRatePlanItems(identityId: String): EitherT[Future, String, List[DynamoSupporterRatePlanItem]] = {
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

object DynamoSupporterProductDataService extends LazyLogging {
  val metrics = Metrics("SupporterProductDataService") // referenced in CloudFormation

  def errorMessage(errors: List[DynamoReadError]) =
    s"There were read errors while reading from the SupporterProductData DynamoDB table\n ${errors.map(describe).mkString("\n")}"

  def alertOnDynamoReadErrors(errors: List[DynamoReadError]) =
    if (errors.nonEmpty) {
      logger.error(errorMessage(errors))
      metrics.put("SupporterProductDataDynamoError", 1) // referenced in CloudFormation
    }
}
