package services

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.model.{DeleteItemResult, PutItemResult}
import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import com.gu.scanamo.{DynamoFormat, ScanamoAsync, Table}
import com.gu.scanamo.error.{DynamoReadError, MissingProperty}
import com.gu.scanamo.syntax.{set => scanamoSet, _}
import com.gu.scanamo.update.UpdateExpression
import com.typesafe.scalalogging.LazyLogging
import models.DynamoAttributes
import monitoring.Metrics
import org.joda.time.{DateTime, LocalDate}

import scala.concurrent.{ExecutionContext, Future}

class ScanamoAttributeService(client: AmazonDynamoDBAsync, table: String)(implicit executionContext: ExecutionContext)
    extends AttributeService with LazyLogging {

  val metrics = Metrics("ScanamoAttributeService") //referenced in CloudFormation

  def checkHealth: Boolean = client.describeTable(table).getTable.getTableStatus == "ACTIVE"

  implicit val jodaStringFormat = DynamoFormat.coercedXmap[LocalDate, String, IllegalArgumentException](
    LocalDate.parse(_)
  )(
    _.toString
  )

  private val scanamo = Table[DynamoAttributes](table)
  def run[T] = ScanamoAsync.exec[T](client) _

  override def get(userId: String): Future[Option[DynamoAttributes]] =
    run(scanamo.get('UserId -> userId).map(_.flatMap {
      _
        .left.map(e => logger.warn("Scanamo error in get: ", e))
        .right.toOption
    }))

  def getMany(userIds: List[String]): Future[Seq[DynamoAttributes]] =
    run(scanamo.getAll('UserId -> userIds.toSet)).map(_.flatMap{
      _
        .left.map(e => logger.warn("Scanamo error in getAll: ", e))
        .right.toOption
    }).map(_.toList)

  override def set(attributes: DynamoAttributes): Future[Option[Either[DynamoReadError, DynamoAttributes]]] = run(scanamo.put(attributes))

  override def update(attributes: DynamoAttributes): Future[Either[DynamoReadError, DynamoAttributes]] = {

    val userId = attributes.UserId
    logger.info(s"New TTL for user ${userId} will be ${attributes.TTLTimestamp}.")

    def scanamoSetOpt[T: DynamoFormat](field: (Symbol, Option[T])): Option[UpdateExpression] = field._2.map(scanamoSet(field._1, _))

    List(
      scanamoSetOpt('Tier, attributes.Tier),
      scanamoSetOpt('RecurringContributionPaymentPlan -> attributes.RecurringContributionPaymentPlan),
      scanamoSetOpt('MembershipJoinDate -> attributes.MembershipJoinDate),
      scanamoSetOpt('DigitalSubscriptionExpiryDate -> attributes.DigitalSubscriptionExpiryDate),
      scanamoSetOpt('PaperSubscriptionExpiryDate -> attributes.PaperSubscriptionExpiryDate),
      scanamoSetOpt('GuardianWeeklySubscriptionExpiryDate -> attributes.GuardianWeeklySubscriptionExpiryDate),
      Some(scanamoSet('TTLTimestamp, attributes.TTLTimestamp))
    ).flatten match {
      case first :: remaining =>
        run(
          scanamo.update(
            'UserId -> userId,
            remaining.fold(first)(_.and(_))
          )
        )

      case Nil =>
        Future.successful(Left(MissingProperty))
    }
  }

  override def delete(userId: String): Future[DeleteItemResult] = run(scanamo.delete('UserId -> userId))

  override def ttlAgeCheck(dynamoAttributes: Option[DynamoAttributes], identityId: String, currentDate: LocalDate): Unit = {
    // Dynamo typically cleans up within 48 hours of expiry. I'm allowing slightly older expiry dates to avoid false alarms
    // https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/howitworks-ttl.html
    val oldestAcceptedAge = currentDate.toDateTimeAtCurrentTime.minusDays(3)
    val tooOld = for {
      attributes <- dynamoAttributes
    } yield TtlConversions.secondsToDateTime(attributes.TTLTimestamp).isBefore(oldestAcceptedAge)
    if (tooOld.contains(true)) {
      SafeLogger.error(scrub"Dynamo Attributes for $identityId have an old TTL. The oldest accepted age is: $oldestAcceptedAge - are we still cleaning the table correctly?")
      metrics.put("Old Dynamo Item", 1) //referenced in CloudFormation
    }
  }

}

object TtlConversions {
  def toDynamoTtlInSeconds(dateTime: DateTime) = dateTime.getMillis / 1000
  def secondsToDateTime(seconds: Long) = new DateTime(seconds * 1000)
}

