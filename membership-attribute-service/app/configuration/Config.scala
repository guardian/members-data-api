package configuration

import java.time.Duration

import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.amazonaws.services.sns.AmazonSNSAsyncClient
import com.amazonaws.services.sqs.AmazonSQSAsyncClient
import com.getsentry.raven.dsn.Dsn
import com.github.dwhjames.awswrap.dynamodb.{AmazonDynamoDBScalaClient, AmazonDynamoDBScalaMapper}
import com.github.dwhjames.awswrap.sns.AmazonSNSScalaClient
import com.github.dwhjames.awswrap.sqs.AmazonSQSScalaClient
import com.gu.aws.CredentialsProvider
import com.gu.identity.cookie.{PreProductionKeys, ProductionKeys}
import com.gu.identity.testing.usernames.{Encoder, TestUsernames}
import com.typesafe.config.ConfigFactory
import play.api.Configuration
import play.filters.cors.CORSConfig

import scala.collection.JavaConverters._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

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

  object authentication {
    val keys = config.getString("authentication.key").split(',')
  }

  lazy val dynamoMapper = {
    val awsDynamoClient = new AmazonDynamoDBAsyncClient(CredentialsProvider)
    awsDynamoClient.configureRegion(AWS.region)
    val dynamoClient = new AmazonDynamoDBScalaClient(awsDynamoClient)
    AmazonDynamoDBScalaMapper(dynamoClient)
  }

  lazy val snsClient = {
    val awsSnsClient = new AmazonSNSAsyncClient(CredentialsProvider)
    awsSnsClient.configureRegion(AWS.region)
    val snsClient = new AmazonSNSScalaClient(awsSnsClient)
    snsClient
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
    allowedOrigins = config.getStringList("mma.cors.allowedOrigins").asScala.toSet
  )

  val ftCorsConfig = CORSConfig.denyAll.copy(
    allowedOrigins = config.getStringList("ft.cors.allowedOrigins").asScala.toSet
  )

  lazy val mmaCardCorsConfig = Config.mmaCorsConfig.copy(
    isHttpHeaderAllowed = Seq("accept", "content-type", "csrf-token", "origin").contains(_),
    isHttpMethodAllowed = _ == "POST",
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
