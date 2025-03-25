package services.stripe

import com.gu.i18n.Country
import com.gu.okhttp.RequestRunners
import com.gu.stripe.StripeServiceConfig
import monitoring.CreateMetrics

import scala.concurrent.ExecutionContext

case class StripePublicKey(key: String)

object ChooseStripe {
  def createFor(ukStripeConfig: StripeServiceConfig, auServiceConfig: StripeServiceConfig, tortoiseMediaStripeServiceConfig: StripeServiceConfig)(
      implicit ec: ExecutionContext,
  ): ChooseStripe = {
    val ukStripePublicKey: StripePublicKey = StripePublicKey(ukStripeConfig.credentials.publicKey)
    val auStripePublicKey: StripePublicKey = StripePublicKey(auServiceConfig.credentials.publicKey)
    val tortoiseMediaStripePublicKey: StripePublicKey = StripePublicKey(tortoiseMediaStripeServiceConfig.credentials.publicKey)

    val ukStripeService: StripeService = createStripeServiceFor(ukStripeConfig)
    val auStripeService: StripeService = createStripeServiceFor(auServiceConfig)
    val tortoiseMediaStripeService: StripeService = createStripeServiceFor(tortoiseMediaStripeServiceConfig)

    val stripePublicKeyByCountry: Map[Country, StripePublicKey] = Map(
      Country.UK -> ukStripePublicKey,
      Country.Australia -> auStripePublicKey,
    )
    val stripeServicesByPublicKey: Map[StripePublicKey, StripeService] = Map(
      ukStripePublicKey -> ukStripeService,
      auStripePublicKey -> auStripeService,
      tortoiseMediaStripePublicKey -> tortoiseMediaStripeService,
    )
    new ChooseStripe(stripePublicKeyByCountry, ukStripePublicKey, stripeServicesByPublicKey)
  }

  private def createStripeServiceFor(stripeConfig: StripeServiceConfig)(implicit ec: ExecutionContext) = {
    val basicUkStripeService = HttpBasicStripeService.from(stripeConfig, RequestRunners.futureRunner)
    new StripeService(stripeConfig, basicUkStripeService)
  }
}

class ChooseStripe(
    publicKeyMappings: Map[Country, StripePublicKey],
    defaultKey: StripePublicKey,
    serviceMappings: Map[StripePublicKey, StripeService],
) {
  def publicKeyForCountry(country: Option[Country]): StripePublicKey = country.flatMap(publicKeyMappings.get).getOrElse(defaultKey)
  def serviceForPublicKey(publicKey: String): Option[StripeService] = serviceForPublicKey(StripePublicKey(publicKey))
  def serviceForPublicKey(publicKey: StripePublicKey): Option[StripeService] = serviceMappings.get(publicKey)
}
