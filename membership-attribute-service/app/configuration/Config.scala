package configuration

import java.security.cert.CertificateFactory

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, InstanceProfileCredentialsProvider}
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.github.dwhjames.awswrap.dynamodb.{AmazonDynamoDBScalaClient, AmazonDynamoDBScalaMapper}
import com.gu.identity.cookie.{PreProductionKeys, ProductionKeys}
import com.typesafe.config.ConfigFactory
import play.api.{Play, Logger}

import scala.concurrent.ExecutionContext.Implicits.global

object Config {
  private val logger = Logger(this.getClass)

  val config = ConfigFactory.load()

  logger.info(s"Stage=${config.getString("stage")}")

  val idKeys = if (config.getBoolean("identity.production.keys")) new ProductionKeys else new PreProductionKeys
  val dynamoTable = config.getString("dynamodb.table")
  val useFixtures = config.getBoolean("use-fixtures")

  val salesforceCert = config.getString("salesforce.certificate")

  lazy val dynamoMapper = {
    val awsProfile = config.getString("aws-profile")
    val awsCredentialsProvider = new AWSCredentialsProviderChain(new ProfileCredentialsProvider(awsProfile), new InstanceProfileCredentialsProvider())

    val awsDynamoClient = new AmazonDynamoDBAsyncClient(awsCredentialsProvider.getCredentials)
    awsDynamoClient.configureRegion(Regions.EU_WEST_1)
    val dynamoClient = new AmazonDynamoDBScalaClient(awsDynamoClient)
    AmazonDynamoDBScalaMapper(dynamoClient)
  }
}
