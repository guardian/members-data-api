package configuration

import java.time.Duration

import com.amazonaws.regions.Regions
import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder
import io.sentry.dsn.Dsn
import com.gu.aws.CredentialsProvider
import com.gu.identity.testing.usernames.{Encoder, TestUsernames}
import com.typesafe.config.ConfigFactory
import play.api.Configuration
import play.filters.cors.CORSConfig

import scala.util.Try

object Config {

  val config = ConfigFactory.load()
  val applicationName = "members-data-api"

  val stage = config.getString("stage")

  val useFixtures = config.getBoolean("use-fixtures")
  lazy val sentryDsn = Try(config.getString("sentry.dsn")).toOption

  object AWS {
    val region = Regions.EU_WEST_1
  }

  lazy val sqsClient = AmazonSQSAsyncClientBuilder
      .standard
      .withCredentials(CredentialsProvider)
      .withRegion(AWS.region)
      .build()


  lazy val testUsernames = TestUsernames(Encoder.withSecret(config.getString("identity.test.users.secret")), Duration.ofDays(2))

  val defaultTouchpointBackendStage = config.getString("touchpoint.backend.default")
  val testTouchpointBackendStage = config.getString("touchpoint.backend.test")
  val corsConfig = CORSConfig.fromConfiguration(Configuration(config))

  lazy val mmaUpdateCorsConfig = corsConfig.copy(
    isHttpHeaderAllowed = Seq("accept", "content-type", "csrf-token", "origin").contains(_),
    isHttpMethodAllowed = Seq("POST","OPTIONS").contains(_)
  )

  object Logstash {
    private val param = Try{config.getConfig("param.logstash")}.toOption
    val stream = Try{param.map(_.getString("stream"))}.toOption.flatten
    val streamRegion = Try{param.map(_.getString("streamRegion"))}.toOption.flatten
    val enabled = Try{config.getBoolean("logstash.enabled")}.toOption.contains(true)
  }

  object Mobile {
    val subscriptionApiKey: String = config.getString("mobile.subscription.apiKey")
  }

}
