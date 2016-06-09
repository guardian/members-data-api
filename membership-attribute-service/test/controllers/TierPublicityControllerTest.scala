package controllers
import models.Attributes
import services.AttributeService
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import org.specs2.mutable.Specification

class TierPublicityControllerTest extends Specification {

  val ctrl = new TierPublicityController()
  val dynamoStub = new AttributeService {
    override def delete(userId: String): Future[Unit] = ???
    override def set(attributes: Attributes): Future[Unit] = ???
    override def get(userId: String): Future[Option[Attributes]] = ???
    override def getMany(userIds: List[String]): Future[Seq[Attributes]] = Future.successful(Seq(
      Attributes("1234", "Partner", None, Some(true)),
      Attributes("1235", "Partner", None, Some(false)),
      Attributes("1236", "Partner", None, None)
    ))
  }

  "getPublicAttributes" should {
    "Only show the tier information of people explicitly opted in" in {
      Await.result(ctrl.getPublicTiers(List("1234", "1235", "1236"), dynamoStub), 5.seconds) mustEqual Map("1234" -> "Partner")
    }
  }
}
