package services

import models.MembershipAttributes
import org.joda.time.LocalDate
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration._

class AttributeServiceTest extends Specification {

  val service = new AttributeService

  "getAttributes" should {
    // place holder test to demo testing ApiResponse result
    "return ApiResponse with Right result" in {
      val userId = "123"
      val result = service.getAttributes(userId)

      Await.result(result.underlying, 1.second) shouldEqual Right(MembershipAttributes(LocalDate.now, "patron", "1234"))
    }
  }

}
