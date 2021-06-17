package services

import com.gu.monitoring.SafeLogger
import com.gu.monitoring.SafeLogger._
import com.typesafe.scalalogging.LazyLogging
import models.DynamoAttributes
import monitoring.Metrics
import org.joda.time.{DateTime, LocalDate}
import org.scanamo.generic.semiauto.deriveDynamoFormat
import org.scanamo.query.AttributeName
import org.scanamo.syntax.{set => scanamoSet, _}
import org.scanamo.update.UpdateExpression
import org.scanamo._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.{DescribeTableRequest, TableStatus}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds

class ScanamoAttributeService(client: DynamoDbAsyncClient, table: String)(implicit executionContext: ExecutionContext)
    extends AttributeService with LazyLogging {

  val metrics = Metrics("ScanamoAttributeService") //referenced in CloudFormation

  def checkHealth: Boolean = client.describeTable(DescribeTableRequest.builder().tableName(table).build()).get.table().tableStatus() == TableStatus.ACTIVE

  implicit val jodaStringFormat = DynamoFormat.coercedXmap[LocalDate, String, IllegalArgumentException](
    LocalDate.parse(_),
    _.toString
  )

  implicit val dynamoAttributes: DynamoFormat[DynamoAttributes] = deriveDynamoFormat

  private val scanamo = Table[DynamoAttributes](table)
  def run[T] = ScanamoAsync(client).exec[T] _

  override def get(userId: String): Future[Option[DynamoAttributes]] =
    run(scanamo.get("UserId" === userId).map(_.flatMap {
      _
        .left.map(e => logger.warn("Scanamo error in get: ", e))
        .right.toOption
    }))

  def getMany(userIds: List[String]): Future[Seq[DynamoAttributes]] =
    run(scanamo.getAll("UserId" in userIds.toSet)).map(_.flatMap{
      _
        .left.map(e => logger.warn("Scanamo error in getAll: ", e))
        .right.toOption
    }).map(_.toList)

  override def set(attributes: DynamoAttributes): Future[Unit] = run(scanamo.put(attributes))

  override def update(attributes: DynamoAttributes): Future[Either[DynamoReadError, DynamoAttributes]] = {

    val userId = attributes.UserId
    logger.info(s"New TTL for user ${userId} will be ${attributes.TTLTimestamp}.")

    def scanamoSetOpt[T: DynamoFormat](field: (AttributeName, Option[T])): Option[UpdateExpression] = field._2.map(scanamoSet(field._1, _))

    List(
      scanamoSetOpt("Tier", attributes.Tier),
      scanamoSetOpt("RecurringContributionPaymentPlan" -> attributes.RecurringContributionPaymentPlan),
      scanamoSetOpt("MembershipJoinDate" -> attributes.MembershipJoinDate),
      scanamoSetOpt("DigitalSubscriptionExpiryDate" -> attributes.DigitalSubscriptionExpiryDate),
      scanamoSetOpt("PaperSubscriptionExpiryDate" -> attributes.PaperSubscriptionExpiryDate),
      scanamoSetOpt("GuardianWeeklySubscriptionExpiryDate" -> attributes.GuardianWeeklySubscriptionExpiryDate),
      Some(scanamoSet("TTLTimestamp", attributes.TTLTimestamp))
    ).flatten match {
      case first :: remaining =>
        run(
          scanamo.update(
            "UserId" === userId,
            remaining.fold(first)(_.and(_))
          )
        )

      case Nil =>
        Future.successful(Left(MissingProperty))
    }
  }

  override def delete(userId: String): Future[Unit] = run(scanamo.delete("UserId" === userId))

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

