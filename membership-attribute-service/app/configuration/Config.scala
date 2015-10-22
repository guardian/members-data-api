package configuration

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, InstanceProfileCredentialsProvider}
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.github.dwhjames.awswrap.dynamodb.{AmazonDynamoDBScalaClient, AmazonDynamoDBScalaMapper}
import com.github.nscala_time.time.Imports._
import com.gu.identity.cookie.{PreProductionKeys, ProductionKeys}
import com.gu.identity.testing.usernames.{Encoder, TestUsernames}
import com.typesafe.config.ConfigFactory
import net.kencochrane.raven.dsn.Dsn
import play.api.Configuration
import play.filters.cors.CORSConfig

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try
import scala.collection.JavaConverters._

object Config {
  val config = ConfigFactory.load()

  case class SalesforceConfig(secret: String, organizationId: String) {
    // Salesforce provides a "display" id, 15 characters long, and a "real" id, with 3 characters appended.
    // They don't provide a particular name to distinguish between the two.
    require(organizationId.length == 18)
  }

  case class BackendConfig(dynamoTable: String, salesforceConfig: SalesforceConfig)

  object BackendConfig {
    private def forEnvironment(env: String): BackendConfig = {
      if (!Seq("default", "test").contains(env)) throw new IllegalArgumentException("The environment should be either default or test")
      val backendEnv = config.getString(s"touchpoint.backend.$env")
      val backendConf = config.getConfig(s"touchpoint.backend.environments.$backendEnv")
      val dynamoTable = backendConf.getString("dynamodb.table")
      val salesforceConfig = SalesforceConfig(
        secret = backendConf.getString("salesforce.hook-secret"),
        organizationId = backendConf.getString("salesforce.organization-id")
      )
      BackendConfig(dynamoTable, salesforceConfig)
    }

    lazy val default = BackendConfig.forEnvironment("default")
    lazy val test = BackendConfig.forEnvironment("test")
  }

  val applicationName = "members-data-api"

  val stage = config.getString("stage")

  val idKeys = if (config.getBoolean("identity.production.keys")) new ProductionKeys else new PreProductionKeys
  val useFixtures = config.getBoolean("use-fixtures")
  lazy val sentryDsn = Try(new Dsn(config.getString("sentry.dsn")))

  object AWS {
    val profile = config.getString("aws-profile")
    val credentialsProvider = new AWSCredentialsProviderChain(new ProfileCredentialsProvider(profile), new InstanceProfileCredentialsProvider())
    val region = Regions.EU_WEST_1
  }

  lazy val dynamoMapper = {
    val awsDynamoClient = new AmazonDynamoDBAsyncClient(AWS.credentialsProvider)
    awsDynamoClient.configureRegion(AWS.region)
    val dynamoClient = new AmazonDynamoDBScalaClient(awsDynamoClient)
    AmazonDynamoDBScalaMapper(dynamoClient)
  }

  lazy val testUsernames = TestUsernames(Encoder.withSecret(config.getString("identity.test.users.secret")), 2.days.toStandardDuration)

  val corsConfig = CORSConfig.fromConfiguration(Configuration(config))

  // TODO: remove once the adfree feature is generally available to the public
  val preReleaseUsersIds = config.getStringList("identity.prerelease-users").asScala.toSet
}
