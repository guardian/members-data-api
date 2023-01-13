package services

import com.gu.i18n.Currency
import com.typesafe.scalalogging.LazyLogging
import models.{Attributes, DynamoSupporterRatePlanItem}
import monitoring.CreateMetrics
import org.joda.time.{DateTimeZone, LocalDate}
import org.scanamo.DynamoReadError.describe
import org.scanamo._
import org.scanamo.generic.semiauto._
import org.scanamo.syntax._
import scalaz.std.scalaFuture._
import scalaz.{EitherT, \/}
import services.DynamoSupporterProductDataService.errorMessage
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import utils.SimpleEitherT.SimpleEitherT

import scala.concurrent.{ExecutionContext, Future}

trait SupporterProductDataService {
  def getNonCancelledAttributes(identityId: String): Future[Either[String, Option[Attributes]]]
  def getSupporterRatePlanItems(identityId: String): SimpleEitherT[List[DynamoSupporterRatePlanItem]]
}

class DynamoSupporterProductDataService(
    client: DynamoDbAsyncClient,
    table: String,
    mapper: SupporterRatePlanToAttributesMapper,
    createMetrics: CreateMetrics,
)(implicit
    executionContext: ExecutionContext,
) extends SupporterProductDataService
    with LazyLogging {
  val metrics = createMetrics.forService(classOf[SupporterProductDataService]) // referenced in CloudFormation

  implicit val jodaStringFormat: DynamoFormat[LocalDate] =
    DynamoFormat.coercedXmap[LocalDate, String, IllegalArgumentException](LocalDate.parse, _.toString)
  implicit val currencyFormat: DynamoFormat[Currency] =
    DynamoFormat.xmap[Currency, String](s => Currency.fromString(s).toRight(TypeCoercionError(new Throwable("Invalid currency"))), _.iso)
  implicit val dynamoSupporterRatePlanItem: DynamoFormat[DynamoSupporterRatePlanItem] = deriveDynamoFormat

  def getNonCancelledAttributes(identityId: String): Future[Either[String, Option[Attributes]]] = {
    getSupporterRatePlanItems(identityId).map { ratePlanItems =>
      val nonCancelled = ratePlanItems.filter(item => !item.cancellationDate.exists(_.isBefore(LocalDate.now(DateTimeZone.UTC))))
      mapper.attributesFromSupporterRatePlans(identityId, nonCancelled)
    }.toEither
  }

  def getSupporterRatePlanItems(identityId: String): SimpleEitherT[List[DynamoSupporterRatePlanItem]] = {
    EitherT(
      for {
        futureDynamoResult <- getSupporterRatePlanItemsWithReadErrors(identityId)
        futureErrors = futureDynamoResult.collect { case Left(error) =>
          error
        }
        _ = alertOnDynamoReadErrors(identityId, futureErrors)
        futureRatePlanItems = futureDynamoResult.collect({ case Right(ratePlanItem) =>
          ratePlanItem
        })
      } yield
        if (futureErrors.isEmpty || futureRatePlanItems.nonEmpty)
          \/.right(futureRatePlanItems)
        else
          \/.left(errorMessage(identityId, futureErrors)),
    )
  }

  private def getSupporterRatePlanItemsWithReadErrors(identityId: String): Future[List[Either[DynamoReadError, DynamoSupporterRatePlanItem]]] =
    ScanamoAsync(client).exec {
      Table[DynamoSupporterRatePlanItem](table)
        .query("identityId" === identityId)
    }

  private def alertOnDynamoReadErrors(identityId: String, errors: List[DynamoReadError]) =
    if (errors.nonEmpty) {
      logger.error(errorMessage(identityId, errors))
      metrics.put("SupporterProductDataDynamoError", 1) // referenced in CloudFormation
    }
}

object DynamoSupporterProductDataService {
  def errorMessage(identityId: String, errors: List[DynamoReadError]) =
    s"There were read errors while reading from the SupporterProductData DynamoDB table " +
      s"for user $identityId\n ${errors.map(describe).mkString("\n")}"
}
