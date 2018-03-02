package testdata

import com.gu.i18n.Currency.GBP
import com.gu.memsub.Subscription.AccountId
import com.gu.zuora.rest.ZuoraRestService.{AccountObject, PaymentMethodId}

object AccountObjectTestData {
  private val testAccountId = AccountId("accountId")
  private val testPaymentMethodId = PaymentMethodId("testme")
  private val testIdentityId = "123"
  private val currency = GBP
  val accountObjectWithBalance = AccountObject(testAccountId, 20.0, Some(currency), Some(testPaymentMethodId))
  val accountObjectWithZeroBalance = AccountObject(testAccountId, 0, Some(currency), Some(testPaymentMethodId))
}