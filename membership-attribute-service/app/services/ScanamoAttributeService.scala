package services

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync
import com.amazonaws.services.dynamodbv2.model.{DeleteItemResult, PutItemResult}
import com.gu.scanamo._
import com.gu.scanamo.error.{DynamoReadError, MissingProperty}
import com.gu.scanamo.syntax.{set => scanamoSet, _}
import com.gu.scanamo.update.UpdateExpression
import com.typesafe.scalalogging.LazyLogging
import models.Attributes
import org.joda.time.{DateTime, LocalDate}
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future

class ScanamoAttributeService(client: AmazonDynamoDBAsync, table: String, timestamper: => DateTime = Timestamper.expiryDate)
    extends AttributeService with LazyLogging {

  def checkHealth: Boolean = client.describeTable(table).getTable.getTableStatus == "ACTIVE"

  implicit val jodaStringFormat = DynamoFormat.coercedXmap[LocalDate, String, IllegalArgumentException](
    LocalDate.parse(_)
  )(
    _.toString
  )

  private val scanamo = Table[Attributes](table)
  def run[T] = ScanamoAsync.exec[T](client) _

  override def get(userId: String): Future[Option[Attributes]] =
    run(scanamo.get('UserId -> userId).map(_.flatMap {
      _
        .left.map(e => logger.warn("Scanamo error in get: ", e))
        .right.toOption
    }))

  def getMany(userIds: List[String]): Future[Seq[Attributes]] =
    run(scanamo.getAll('UserId -> userIds.toSet)).map(_.flatMap{
      _
        .left.map(e => logger.warn("Scanamo error in getAll: ", e))
        .right.toOption
    }).map(_.toList)

  override def set(attributes: Attributes): Future[PutItemResult] = run(scanamo.put(attributes))

  override def update(attributes: Attributes): Future[Either[DynamoReadError, Attributes]] = {
    val currentExpiry: Option[DateTime] = attributes.TTLTimestamp map { timestamp => Timestamper.toDateTime(timestamp) }

    val newExpiry: DateTime = currentExpiry.map { expiry =>
      if (timestamper.isAfter(expiry.plusDays(1))) timestamper else expiry
    }.getOrElse(timestamper)

    def scanamoSetOpt[T: DynamoFormat](field: (Symbol, Option[T])): Option[UpdateExpression] = field._2.map(scanamoSet(field._1, _))

    List(
      scanamoSetOpt('Tier, attributes.Tier),
      scanamoSetOpt('MembershipNumber -> attributes.MembershipNumber),
      scanamoSetOpt('RecurringContributionPaymentPlan -> attributes.RecurringContributionPaymentPlan),
      scanamoSetOpt('Wallet -> attributes.Wallet),
      scanamoSetOpt('MembershipJoinDate -> attributes.MembershipJoinDate),
      scanamoSetOpt('DigitalSubscriptionExpiryDate -> attributes.DigitalSubscriptionExpiryDate),
      scanamoSetOpt('TTLTimestamp -> Option(Timestamper.toDynamoTtl(newExpiry)))
    ).flatten match {
      case first :: remaining =>
        run(
          scanamo.update(
            'UserId -> attributes.UserId,
            remaining.fold(first)(_.and(_))
          )
        )

      case Nil =>
        Future.successful(Left(MissingProperty))
    }
  }

  override def delete(userId: String): Future[DeleteItemResult] = run(scanamo.delete('UserId -> userId))
}

object Timestamper {
  def expiryDate: DateTime = DateTime.now().plusDays(7)
  def toDynamoTtl(date: DateTime) = date.getMillis / 1000
  def toDateTime(seconds: Long) = new DateTime(seconds * 1000)
}

