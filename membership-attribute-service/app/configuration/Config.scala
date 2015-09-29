package configuration

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, InstanceProfileCredentialsProvider}
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.github.dwhjames.awswrap.dynamodb.{AmazonDynamoDBScalaClient, AmazonDynamoDBScalaMapper}
import com.gu.identity.cookie.{PreProductionKeys, ProductionKeys}
import com.typesafe.config.ConfigFactory
import net.kencochrane.raven.dsn.Dsn
import play.api.Logger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

object Config {
  val applicationName = "CASProxy"

  private val logger = Logger(this.getClass)

  val config = ConfigFactory.load()

  val stage = config.getString("stage")

  val idKeys = if (config.getBoolean("identity.production.keys")) new ProductionKeys else new PreProductionKeys
  val dynamoTable = config.getString("dynamodb.table")
  val useFixtures = config.getBoolean("use-fixtures")
  lazy val sentryDsn = Try(new Dsn(config.getString("sentry.dsn")))

  val salesforceSecret = config.getString("salesforce.hook-secret")

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
}
