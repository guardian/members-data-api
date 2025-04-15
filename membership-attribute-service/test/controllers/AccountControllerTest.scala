package controllers

import actions.CommonActions
import monitoring.CreateNoopMetrics
import org.mockito.IdiomaticMockito
import org.specs2.mutable.Specification
import play.api.test.Helpers._
import play.api.test._
import services.FakePostgresService
import services.mail.SendEmail

class AccountControllerTest extends Specification with IdiomaticMockito {

  "validateContributionAmountUpdateForm" should {

    val subName = "s1"
    val commonActions = mock[CommonActions]
    val controller =
      new AccountController(commonActions, stubControllerComponents(), FakePostgresService("123"), mock[SendEmail], CreateNoopMetrics, isProd = false)
    val request = FakeRequest("POST", s"/api/update/amount/contributions/$subName")

    "succeed when given value is valid" in {
      val result = controller.validateContributionAmountUpdateForm(request.withFormUrlEncodedBody("newPaymentAmount" -> "1"))
      result must beRight(1)
    }

    "fail when no given value" in {
      val result = controller.validateContributionAmountUpdateForm(request)
      result must beLeft("no new payment amount submitted with request")
    }

    "fail when given value is zero" in {
      val result = controller.validateContributionAmountUpdateForm(request.withFormUrlEncodedBody("newPaymentAmount" -> "0"))
      result must beLeft("New payment amount '0.00' is too small")
    }
  }
}
