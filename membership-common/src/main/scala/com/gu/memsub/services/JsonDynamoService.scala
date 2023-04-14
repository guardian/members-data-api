package com.gu.memsub.services

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProviderChain, EnvironmentVariableCredentialsProvider, InstanceProfileCredentialsProvider}
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDBClient, AmazonDynamoDBClientBuilder}
import com.amazonaws.services.dynamodbv2.document.spec.{GetItemSpec, QuerySpec, ScanSpec}
import com.amazonaws.services.dynamodbv2.document._
import com.amazonaws.services.dynamodbv2.model.TableDescription
import com.gu.aws.CredentialsProvider
import play.api.libs.json._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.higherKinds
import scala.util.Try
import scalaz.Monad

class JsonDynamoService[A, M[_]](table: Table)(implicit m: Monad[M]) {

  implicit val itemFormat = JsonDynamoService.itemFormat

  def all(implicit formatter: Reads[A]): M[Seq[A]] = Monad[M].point {
    val items: Iterator[Item] = table.scan().iterator().asScala
    items.flatMap(i => Json.fromJson[A](Json.toJson[Item](i)).asOpt).toList
  }

  def add(p: A)(implicit formatter: Writes[A]): M[Unit] = Monad[M].point {
    val item = Json.fromJson[Item](Json.toJson(p))
      .getOrElse(throw new IllegalStateException(s"Unable to convert $p to item"))
    table.putItem(item)
    ()
  }

  def find[B](b: B)(implicit of: OWrites[B], r: Reads[A]): M[List[A]] = Monad[M].point {
    val primaryKey = table.describe().getKeySchema.get(0).getAttributeName
    val jsonItem = Json.toJson(b)
    val dynamoResult = (jsonItem \ primaryKey).validate[String].asOpt.fold {
      itemFormat.reads(jsonItem).fold(err => Seq.empty, { itemFromJson =>
        val filters = itemFromJson.asMap().asScala.map { case (k, v) => new ScanFilter(k).eq(v): ScanFilter }.toSeq
        table.scan(new ScanSpec().withScanFilters(filters:_*)).iterator().asScala.toSeq
      })
    } { keyValue =>
      Option(table.getItem(new GetItemSpec().withPrimaryKey(primaryKey, keyValue))).toSeq
    }
    dynamoResult.flatMap(i => Json.fromJson[A](Json.toJson[Item](i)).asOpt).toList
  }
}

object JsonDynamoService {

  val itemFormat = Format(
    new Reads[Item] {
      def reads(in: JsValue): JsResult[Item] =
        Try(JsSuccess(Item.fromJSON(in.toString))).getOrElse(JsError(s"unable to deserialise $in"))
    },
    new Writes[Item] {
      def writes(o: Item): JsValue = Json.parse(o.toJSON)
    }
  )

  def forTable[A](table: String)(implicit e: ExecutionContext): JsonDynamoService[A, Future] = {
    import scalaz.std.scalaFuture._
    val dynamoDBClient = AmazonDynamoDBClient.builder
      .withCredentials(CredentialsProvider)
      .withRegion(Regions.EU_WEST_1)
      .build()
    new JsonDynamoService[A, Future](new DynamoDB(dynamoDBClient).getTable(table))(futureInstance)
  }

}
