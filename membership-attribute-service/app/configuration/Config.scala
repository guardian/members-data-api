package configuration

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, InstanceProfileCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClient
import com.github.dwhjames.awswrap.dynamodb.{AmazonDynamoDBScalaMapper, AmazonDynamoDBScalaClient}
import com.gu.identity.cookie.{ProductionKeys, PreProductionKeys}
import com.typesafe.config.ConfigFactory
import play.api.Logger
import scala.concurrent.ExecutionContext.Implicits.global

object Config {

  private val logger = Logger(this.getClass)

  val config = ConfigFactory.load()

  logger.info(s"Stage=${config.getString("stage")}")

  val idKeys = if (config.getBoolean("identity.production.keys")) new ProductionKeys else new PreProductionKeys
  val dynamoTable = config.getString("dynamodb.table")

  lazy val dynamoMapper = {
    val awsCredentialsProvider = new AWSCredentialsProviderChain(new ProfileCredentialsProvider("identity"), new InstanceProfileCredentialsProvider())

    val awsDynamoClient = new AmazonDynamoDBAsyncClient(awsCredentialsProvider.getCredentials)
    awsDynamoClient.configureRegion(Regions.EU_WEST_1)
    val dynamoClient    = new AmazonDynamoDBScalaClient(awsDynamoClient)
    AmazonDynamoDBScalaMapper(dynamoClient)
  }


}
