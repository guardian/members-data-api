package services

import com.github.dwhjames.awswrap.sns.AmazonSNSScalaClient
import com.gu.stripe.Stripe
import com.gu.stripe.Stripe.{Event, StripeObject}
import configuration.Config
import play.api.libs.json.Json

object SNSGiraffeService {
  def apply(arn: String): SNSGiraffeService = new SNSGiraffeService(Config.snsClient, arn)
}

class SNSGiraffeService(snsClient: AmazonSNSScalaClient, arn: String) {


   def publish(charge: Stripe.ChargeWithBalanceAndCountry): Unit = {
    snsClient.publish(arn, Json.toJson(charge).toString)
  }

}