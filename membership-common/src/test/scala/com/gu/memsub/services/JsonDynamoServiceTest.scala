package com.gu.memsub.services

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.dynamodbv2.document.DynamoDB
import com.amazonaws.services.dynamodbv2.model._
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDB, AmazonDynamoDBClient}
import com.gu.memsub.Subscription.ProductRatePlanId
import com.gu.memsub.promo.Formatters.Common._
import com.gu.memsub.promo.Formatters.PromotionFormatters._
import com.gu.memsub.promo.Promotion.AnyPromotion
import com.gu.memsub.promo.PromotionStub._
import com.gu.memsub.promo._
import org.joda.time.Days
import org.specs2.mutable.Specification

import scala.collection.JavaConverters._
import scalaz.Id.Id

class JsonDynamoServiceTest extends Specification {

  "DynamoDB backed promotion storage" should {

    val prpId = ProductRatePlanId("Test")
    val client: AmazonDynamoDB = AmazonDynamoDBClient.builder
      .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("foo", "bar")))
      .withEndpointConfiguration(new EndpointConfiguration("http://localhost:8000", "eu-west-1"))
      .build()

    val dynamo = new DynamoDB(client)
    val table = dynamo.createTable("tests",
      Seq(new KeySchemaElement("uuid", KeyType.HASH)).asJava,
      Seq(new AttributeDefinition("uuid", ScalarAttributeType.S)).asJava,
      new ProvisionedThroughput(50L, 50L)
    )

    val service = new JsonDynamoService[AnyPromotion, Id](table)
    val promos = Seq(
      promoFor("PARTNER99", prpId),
      promoFor("PARTNER99", prpId).ofType(PercentDiscount(None, 40)).withCampaign("Hello"),
      promoFor("PARTNER99", prpId).ofType(FreeTrial(Days.days(40))).withCampaign("Hello"),
      promoFor("PARTNER99", prpId).ofType(Incentive("foo", Some("bar"), None))
    ).sortBy(_.uuid)
    promos.foreach(service.add)

    "be able to fetch and parse Promotions from DynamoDB" in {
      val output = service.all.sortBy(_.uuid)
      output mustEqual promos
    }

    "Be able to search for promotions by primary key" in {
      val out = service.find(promos.head.uuid)
      out mustEqual List(promos.head)
    }

    "Be able to filter for promotions based on campaign ID" in {
      val out = service.find(CampaignCode("Hello"))
      out.sortBy(_.uuid) mustEqual promos.filter(_.campaign == CampaignCode("Hello")).sortBy(_.uuid)
    }

    "Not break when there are no results" in {
      service.find(CampaignCode("Manatee")) mustEqual List()
    }
  }
}
