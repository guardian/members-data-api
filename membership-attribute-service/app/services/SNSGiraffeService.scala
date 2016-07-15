package services

import com.github.dwhjames.awswrap.sns.AmazonSNSScalaClient
import com.gu.stripe.Stripe
import com.gu.stripe.Stripe._
import configuration.Config
import play.api.libs.json.{Json}

object SNSGiraffeService {
  def apply(arn: String): SNSGiraffeService = new SNSGiraffeService(Config.snsClient, arn)
}

class SNSGiraffeService(snsClient: AmazonSNSScalaClient, arn: String) {
  implicit val sourceFormat = Json.format[Source]
  implicit val writesCharge = Json.writes[Stripe.Charge]
  implicit val writesBalanceTransaction = Json.writes[Stripe.BalanceTransaction]


   def publish(charge: Stripe.Charge, balanceTransaction: BalanceTransaction) : Unit = {
     val json = Json.toJson(Map("charge" -> Json.toJson(charge), "balanceTransaction" -> Json.toJson(balanceTransaction))).toString
     snsClient.publish(arn, json)
  }

}