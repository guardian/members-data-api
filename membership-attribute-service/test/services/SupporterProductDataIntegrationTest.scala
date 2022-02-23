package services

import akka.actor.ActorSystem
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder
import com.gu.aws.ProfileName
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.typesafe.scalalogging.LazyLogging
import components.TouchpointComponents
import models.{Attributes, ContentAccess}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import services.AttributesFromZuora.contentAccessMatches
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProviderChain, EnvironmentVariableCredentialsProvider, InstanceProfileCredentialsProvider, ProfileCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.{DynamoDbAsyncClient, DynamoDbAsyncClientBuilder}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.io.Source

class SupporterProductDataIntegrationTest(implicit ee: ExecutionEnv) extends Specification with LazyLogging {

  val stage = "DEV" // Whichever stage is specified here, you will need config for it in /etc/gu/members-data-api.private.conf
  lazy val CredentialsProvider =  AwsCredentialsProviderChain.builder.credentialsProviders(
    ProfileCredentialsProvider.builder.profileName(ProfileName).build,
    InstanceProfileCredentialsProvider.builder.asyncCredentialUpdateEnabled(false).build,
    EnvironmentVariableCredentialsProvider.create()
  ).build

  lazy val dynamoClientBuilder: DynamoDbAsyncClientBuilder = DynamoDbAsyncClient.builder
    .credentialsProvider(CredentialsProvider)
    .region(Region.EU_WEST_1)
  lazy val mapper = new SupporterRatePlanToAttributesMapper(stage)
  lazy val supporterProductDataTable = s"SupporterProductData-$stage"
  lazy val supporterProductDataService = new SupporterProductDataService(dynamoClientBuilder.build(), supporterProductDataTable, mapper)

  lazy val dynamoAttributesTable = s"SupporterAttributesFallback-$stage"
  lazy val attrService: AttributeService = new ScanamoAttributeService(dynamoClientBuilder.build(), dynamoAttributesTable)


  implicit private val actorSystem: ActorSystem = ActorSystem()
  lazy val touchpoint = new TouchpointComponents(stage)
  lazy val attributesFromZuora = new AttributesFromZuora()

  args(skipAll = true) // This test requires credentials so won't run on CI, change skipAll to false to run locally

  "SupporterProductData" should {
    "match Zuora" in {
      val allIds: List[String] = List("testId")

      allIds.grouped(3).map {
        subList =>
          logger.info("------------------------ Running new batch ------------------------------")
          Await.result(runBatch(subList), 20.seconds)
      }.toList.head
    }
  }

  def runBatch(ids: List[String]): Future[MatchResult[List[(Option[Attributes], Option[Attributes])]]] = {
    for {
      fromZuora <- Future.sequence(ids.map(getFromZuora))
      fromSupporterProductData <- Future.sequence(ids.map(supporterProductDataService.getAttributes))
    } yield {
      val allMismatched = compareLists(fromZuora.map(_._2), fromSupporterProductData.map(_.getOrElse(None)))
      logger.info(s"Original list count: ${ids.length}, mismatched count: ${allMismatched.length}")
      allMismatched should beEmpty
    }
  }

  def getFromZuora(identityId: String) = {
    attributesFromZuora.getAttributesFromZuoraWithCacheFallback(
      identityId,
      identityIdToAccounts = id => touchpoint.zuoraRestService.getAccounts(id).map(_.toEither),
      subscriptionsForAccountId = accountId => reads => touchpoint.subService.subscriptionsForAccountId[AnyPlan](accountId)(reads).map(_.toEither),
      giftSubscriptionsForIdentityId = id => touchpoint.zuoraRestService.getGiftSubscriptionRecordsFromIdentityId(id).map(_.toEither),
      dynamoAttributeService = attrService,
      paymentMethodForPaymentMethodId = paymentMethodId => touchpoint.zuoraRestService.getPaymentMethod(paymentMethodId.get).map(_.toEither),
      supporterProductDataService = touchpoint.supporterProductDataService
    )
  }

  def compareLists(fromDynamo: List[Option[Attributes]], fromSupporterProductData:  List[Option[Attributes]]) =
  fromDynamo.zip(fromSupporterProductData).flatMap { case (dynamo, supporter) =>
    if (!contentAccessMatches(dynamo, supporter)) {
      logger.info(
        s"""{
          "zuora": ${Json.toJson(dynamo)},\n""" +
          s"""    "supporter-product-data": ${Json.toJson(supporter)}
        }"""
      )
      Some((dynamo, supporter))
    } else None
  }


}
