package repositories

import cats.data.Xor
import com.gu.scanamo._
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.syntax._
import configuration.Config
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


case class Reputation(userId: String, justGiving: Option[Reputation.JustGiving])

object Reputation {

  case class JustGiving(donationId: Long)

  implicit val writesJustGiving = Json.writes[JustGiving]
  implicit val writesVerification = Json.writes[Reputation]

  val table = Table[Reputation](s"hackday-user-reputation")

  val client = Config.dynamoMapper.client.client

  def getUserVerification(userId: String): Future[Reputation]= {

    for {
      result: Option[Xor[DynamoReadError, Reputation]] <- ScanamoAsync.exec(client)(table.get('userId -> userId))
    } yield {
      result.get.toOption.get
    }
  }

  // TODO ...make this an update, not a put! Scanamo currently doesn't support updates
  def putJustGivingDonation(userId: String, donationId: Long) =
    ScanamoAsync.exec(client)(table.put(Reputation(userId, Some(JustGiving(donationId)))))


}