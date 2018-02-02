package configuration

import java.time.Duration

import com.amazonaws.regions.Regions
import com.amazonaws.services.sqs.AmazonSQSAsyncClient
import com.getsentry.raven.dsn.Dsn
import com.github.dwhjames.awswrap.sqs.AmazonSQSScalaClient
import com.gu.aws.CredentialsProvider
import com.gu.identity.cookie.{PreProductionKeys, ProductionKeys}
import com.gu.identity.testing.usernames.{Encoder, TestUsernames}
import com.typesafe.config.ConfigFactory
import play.api.Configuration
import play.filters.cors.CORSConfig

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.filters.cors.CORSConfig.Origins

import scala.util.Try

object Config {

  val config = ConfigFactory.load()
  val applicationName = "members-data-api"

  val stage = config.getString("stage")

  val idKeys = if (config.getBoolean("identity.production.keys")) new ProductionKeys else new PreProductionKeys
  val useFixtures = config.getBoolean("use-fixtures")
  lazy val sentryDsn = Try(new Dsn(config.getString("sentry.dsn")))

  object AWS {
    val region = Regions.EU_WEST_1
  }

  lazy val sqsClient = {
    val awsSqsClient = new AmazonSQSAsyncClient(CredentialsProvider)
    awsSqsClient.configureRegion(AWS.region)
    val sqsClient = new AmazonSQSScalaClient(awsSqsClient, defaultContext)
    sqsClient
  }

  lazy val testUsernames = TestUsernames(Encoder.withSecret(config.getString("identity.test.users.secret")), Duration.ofDays(2))

  val defaultTouchpointBackendStage = config.getString("touchpoint.backend.default")
  val testTouchpointBackendStage = config.getString("touchpoint.backend.test")
  val corsConfig = CORSConfig.fromConfiguration(Configuration(config))

  val mmaCorsConfig = CORSConfig.denyAll.copy(
    allowedOrigins = Origins.Matching( str =>
      config.getStringList("mma.cors.allowedOrigins").contains(str)
    )
  )

  val ftCorsConfig = CORSConfig.denyAll.copy(
    allowedOrigins = Origins.Matching( str =>
      config.getStringList("ft.cors.allowedOrigins").contains(str)
    )
  )

  lazy val mmaUpdateCorsConfig = Config.mmaCorsConfig.copy(
    isHttpHeaderAllowed = Seq("accept", "content-type", "csrf-token", "origin").contains(_),
    isHttpMethodAllowed = Seq("POST","OPTIONS").contains(_),
    supportsCredentials = true
  )

  val abandonedCartEmailQueue = config.getString("abandoned.cart.email.queue")

  object Logstash {
    private val param = Try{config.getConfig("param.logstash")}.toOption
    val stream = Try{param.map(_.getString("stream"))}.toOption.flatten
    val streamRegion = Try{param.map(_.getString("streamRegion"))}.toOption.flatten
    val enabled = Try{config.getBoolean("logstash.enabled")}.toOption.contains(true)
  }

}
