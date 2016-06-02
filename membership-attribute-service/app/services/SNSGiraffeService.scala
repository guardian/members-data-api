package services

import com.github.dwhjames.awswrap.sns.AmazonSNSScalaClient
import com.gu.stripe.Stripe
import configuration.Config
import play.api.libs.json.Json

object SNSGiraffeService {
  def apply(arn: String): SNSGiraffeService = new SNSGiraffeService(Config.snsClient, arn)
}

class SNSGiraffeService(snsClient: AmazonSNSScalaClient, arn: String) {

  implicit val writesCharge = Json.writes[Stripe.Charge]

  def publish(charge: Stripe.Charge): Unit = {

    val c = charge
    snsClient.publish(arn, Json.toJson(charge).toString)

  }

}