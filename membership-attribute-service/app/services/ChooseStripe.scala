package services

import com.gu.i18n.Country
import com.gu.okhttp.RequestRunners
import com.gu.okhttp.RequestRunners.client
import com.gu.stripe.StripeServiceConfig
import monitoring.CreateMetrics
import services.stripe.{BasicStripeServiceWithMetrics, HttpBasicStripeService, StripeService}

import scala.concurrent.ExecutionContext

case class StripePublicKey(key: String)

object ChooseStripe {
  def createFor(ukStripeConfig: StripeServiceConfig, auServiceConfig: StripeServiceConfig, createMetrics: CreateMetrics)(implicit
      ec: ExecutionContext,
  ): ChooseStripe = {
    val ukStripePublicKey: StripePublicKey = StripePublicKey(ukStripeConfig.credentials.publicKey)
    val auStripePublicKey: StripePublicKey = StripePublicKey(auServiceConfig.credentials.publicKey)

    val ukStripeService: StripeService = createStripeServiceFor(ukStripeConfig, createMetrics)
    val auStripeService: StripeService = createStripeServiceFor(auServiceConfig, createMetrics)

    val stripePublicKeyByCountry: Map[Country, StripePublicKey] = Map(
      Country.UK -> ukStripePublicKey,
      Country.Australia -> auStripePublicKey,
    )
    val stripeServicesByPublicKey: Map[StripePublicKey, StripeService] = Map(
      ukStripePublicKey -> ukStripeService,
      auStripePublicKey -> auStripeService,
    )
    new ChooseStripe(stripePublicKeyByCountry, ukStripePublicKey, stripeServicesByPublicKey)
  }

  private def createStripeServiceFor(stripeConfig: StripeServiceConfig, createMetrics: CreateMetrics)(implicit ec: ExecutionContext) = {
    val basicUkStripeService =
      new BasicStripeServiceWithMetrics(HttpBasicStripeService.from(stripeConfig, RequestRunners.futureRunner), createMetrics)
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
