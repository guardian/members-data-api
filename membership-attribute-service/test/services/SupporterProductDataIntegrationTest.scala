package services

import akka.actor.ActorSystem
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder
import com.gu.memsub.subsv2.SubscriptionPlan.AnyPlan
import com.typesafe.scalalogging.LazyLogging
import components.TouchpointComponents
import models.Attributes
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.MatchResult
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import services.SupporterProductDataIntegrationTest.ids

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.io.Source
import scala.util.Sorting

class SupporterProductDataIntegrationTest(implicit ee: ExecutionEnv) extends Specification with LazyLogging {

  val stage = "PROD"
  lazy val dynamoClientBuilder: AmazonDynamoDBAsyncClientBuilder = AmazonDynamoDBAsyncClientBuilder.standard().withCredentials(com.gu.aws.CredentialsProvider).withRegion(Regions.EU_WEST_1)
  lazy val mapper = new SupporterRatePlanToAttributesMapper(stage)
  lazy val supporterProductDataTable = "SupporterProductData-PROD"
  lazy val supporterProductDataService = new SupporterProductDataService(dynamoClientBuilder.build(), supporterProductDataTable, mapper)

  lazy val dynamoAttributesTable = "SupporterAttributesFallback-PROD"
  lazy val attrService: AttributeService = new ScanamoAttributeService(dynamoClientBuilder.build(), dynamoAttributesTable)


  implicit private val actorSystem: ActorSystem = ActorSystem()
  lazy val touchpoint = new TouchpointComponents("PROD")
  lazy val attributesFromZuora = new AttributesFromZuora()

  def getFromZuora(identityId: String) = {
    attributesFromZuora.getAttributesFromZuoraWithCacheFallback(
      identityId,
      identityIdToAccounts = touchpoint.zuoraRestService.getAccounts,
      subscriptionsForAccountId = accountId => reads => touchpoint.subService.subscriptionsForAccountId[AnyPlan](accountId)(reads),
      giftSubscriptionsForIdentityId = touchpoint.zuoraRestService.getGiftSubscriptionRecordsFromIdentityId,
      dynamoAttributeService = attrService,
      paymentMethodForPaymentMethodId = paymentMethodId => touchpoint.zuoraRestService.getPaymentMethod(paymentMethodId.get),
      supporterProductDataService = touchpoint.supporterProductDataService
    )
  }

  def compareLists(fromDynamo: List[Option[Attributes]], fromSupporterProductData:  List[Option[Attributes]]) =
  fromDynamo.zip(fromSupporterProductData).flatMap { case (dynamo, supporter) =>
    if (AttributesFromZuora.attributesDoNotMatch(dynamo, supporter)) {
      logger.info(
        s"""{"zuora": ${Json.toJson(dynamo)},\n""" +
        s""""supporter-product-data": ${Json.toJson(supporter)}}"""
      )
      Some((dynamo, supporter))
    } else None
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

  "SupporterProductData" should {
    "match Zuora" in {
      val allIds: List[String] = Source.fromURL(getClass.getResource("/identityIds.txt")).mkString.split("\n").toList // List("105066199")

      allIds.grouped(3).map {
        subList =>
          logger.info("------------------------ Running new batch ------------------------------")
          Await.result(runBatch(subList), 20.seconds)
      }.toList.head
    }
  }
}
