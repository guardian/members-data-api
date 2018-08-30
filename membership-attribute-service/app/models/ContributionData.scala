package models

import java.time.LocalDateTime

import io.circe.Encoder
import io.circe.generic.JsonCodec
import io.circe.generic.semiauto.deriveEncoder

case class ContributionData (
  created: String,
  currency: String,
  amount: BigDecimal
)

object ContributionData {
  implicit val encodeA: Encoder[ContributionData] = io.circe.generic.semiauto.deriveEncoder[ContributionData]
}
