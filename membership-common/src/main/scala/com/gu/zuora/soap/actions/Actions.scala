package com.gu.zuora.soap.actions

import com.gu.i18n.Country
import com.gu.zuora.ZuoraSoapConfig
import com.gu.zuora.soap.DateTimeHelpers._
import com.gu.zuora.soap.models.Results._
import org.joda.time.LocalDate.now
import org.joda.time.{DateTime, LocalDate}

import scala.xml.{Elem, Null, _}

object Actions {

  /*
   * TODO: Split up these actions into simple models (In models/Commands) and XmlWriters
   */

  case class Query(query: String, enableLog: Boolean = true) extends Action[QueryResult] {
    override def additionalLogInfo = Map("Query" -> query)
    val body =
      <ns1:query>
        <ns1:queryString>{query}</ns1:queryString>
      </ns1:query>
    override val enableLogging = enableLog
  }
  case class Login(apiConfig: ZuoraSoapConfig) extends Action[Authentication] {
    override val authRequired = false
    val body =
      <api:login>
        <api:username>{apiConfig.username}</api:username>
        <api:password>{apiConfig.password}</api:password>
      </api:login>
    override def sanitized = "<api:login>...</api:login>"
  }

  /** See https://knowledgecenter.zuora.com/BC_Developers/SOAP_API/E_SOAP_API_Calls/update_call
    */
  case class Update(zObjectId: String, objectType: String, fields: Seq[(String, String)]) extends Action[UpdateResult] {
    val objectNamespace = s"ns2:$objectType"

    override def additionalLogInfo = Map("ObjectType" -> objectType)

    override protected val body: Elem =
      <ns1:update>
        <ns1:zObjects  xsi:type={objectNamespace}>
          <ns2:Id>{zObjectId}</ns2:Id>
          {
        fields.map { case (k, v) =>
          Elem("ns2", k, Null, TopScope, false, Text(v))
        }
      }
        </ns1:zObjects>
      </ns1:update>
  }

  case class CreateCreditCardReferencePaymentMethod(
      accountId: String,
      cardId: String,
      customerId: String,
      last4: String,
      cardCountry: Option[Country],
      expirationMonth: Int,
      expirationYear: Int,
      cardType: String,
  ) extends Action[CreateResult] {
    override def additionalLogInfo = Map("AccountId" -> accountId)

    val body =
      <ns1:create>
        <ns1:zObjects xsi:type="ns2:PaymentMethod">
          <ns2:AccountId>{accountId}</ns2:AccountId>
          <ns2:TokenId>{cardId}</ns2:TokenId>
          <ns2:SecondTokenId>{customerId}</ns2:SecondTokenId>
          <ns2:Type>CreditCardReferenceTransaction</ns2:Type>
          {
        cardCountry.map { country =>
          <ns2:CreditCardCountry>{country.alpha2}</ns2:CreditCardCountry>
        } getOrElse NodeSeq.Empty
      }
          <ns2:CreditCardNumber>{last4}</ns2:CreditCardNumber>
          <ns2:CreditCardExpirationMonth>{expirationMonth}</ns2:CreditCardExpirationMonth>
          <ns2:CreditCardExpirationYear>{expirationYear}</ns2:CreditCardExpirationYear>
          {
        // see CreditCardType allowed values
        // https://knowledgecenter.zuora.com/DC_Developers/G_SOAP_API/E1_SOAP_API_Object_Reference/PaymentMethod
        (cardType.toLowerCase.replaceAll(" ", "") match {
          case "mastercard" => Some("MasterCard")
          case "visa" => Some("Visa")
          case "amex" => Some("AmericanExpress")
          case "americanexpress" => Some("AmericanExpress")
          case "discover" => Some("Discover")
          case _ => None // TODO perhaps log invalid card type
        }) map { cardType =>
          <ns2:CreditCardType>{cardType}</ns2:CreditCardType>
        } getOrElse NodeSeq.Empty
      }
        </ns1:zObjects>
      </ns1:create>
  }

  case class CreatePayPalReferencePaymentMethod(accountId: String, payPalBaid: String, email: String) extends Action[CreateResult] {
    override def additionalLogInfo = Map("AccountId" -> accountId)

    val body =
      <ns1:create>
        <ns1:zObjects xsi:type="ns2:PaymentMethod">
          <ns2:AccountId>{accountId}</ns2:AccountId>
          <ns2:PaypalBaid>{payPalBaid}</ns2:PaypalBaid>
          <ns2:PaypalEmail>{email}</ns2:PaypalEmail>
          <ns2:PaypalType>ExpressCheckout</ns2:PaypalType>
          <ns2:Type>PayPal</ns2:Type>
        </ns1:zObjects>
      </ns1:create>
  }

  sealed trait ZuoraNullableId
  object Clear extends ZuoraNullableId
  case class SetTo(value: String) extends ZuoraNullableId

  case class UpdateAccountPayment(
      accountId: String,
      defaultPaymentMethodId: ZuoraNullableId,
      paymentGatewayName: String,
      autoPay: Option[Boolean],
      maybeInvoiceTemplateId: Option[String],
  ) extends Action[UpdateResult] {

    override def additionalLogInfo = Map(
      "AccountId" -> accountId,
      "DefaultPaymentMethodId" -> defaultPaymentMethodId.toString,
      "PaymentGateway" -> paymentGatewayName,
      "AutoPay" -> autoPay.toString,
      "InvoiceTemplateOverride" -> maybeInvoiceTemplateId.mkString,
    )

    // We use two different vals here because order matters in the xml and zuora requires the clearing and settings lines to be in different places
    val (setPaymentMethodLine, clearPaymentMethodLine) = defaultPaymentMethodId match {
      case SetTo(p) => (<ns2:DefaultPaymentMethodId>{p}</ns2:DefaultPaymentMethodId>, NodeSeq.Empty)
      case Clear => (NodeSeq.Empty, <ns2:fieldsToNull>DefaultPaymentMethodId</ns2:fieldsToNull>)
    }

    val autoPayLine = autoPay.map(ap => <ns2:AutoPay>{ap}</ns2:AutoPay>).getOrElse(NodeSeq.Empty)

    val invoiceTemplateLine = maybeInvoiceTemplateId.map(id => <ns2:InvoiceTemplateId>{id}</ns2:InvoiceTemplateId>).getOrElse(NodeSeq.Empty)

    val body =
      <ns1:update>
        <ns1:zObjects xsi:type="ns2:Account">
          {clearPaymentMethodLine}
          <ns2:Id>{accountId}</ns2:Id>
          {setPaymentMethodLine}
          {invoiceTemplateLine}
          {autoPayLine}
          <ns2:PaymentGateway>{paymentGatewayName}</ns2:PaymentGateway>
        </ns1:zObjects>
      </ns1:update>

  }

  /** A hack to get when a subscription charge dates will be effective. While it's possible to get this data from an Invoice of a subscription that
    * charges the user immediately (e.g. annual partner sign up), it's not possible to get this for data for subscriptions that charge in the future
    * (subs offer that charges 6 months in). To achieve the latter an amend call with preview can be used - this works for the first case too
    *
    * The dummy amend also sets the subscription to be evergreen - an infinite term length while the real subscriptions have one year terms. This is
    * so we still get invoices for annual subs which have already been billed
    */
  case class PreviewInvoicesViaAmend(numberOfPeriods: Int = 2)(subscriptionId: String, paymentDate: LocalDate = now) extends Action[AmendResult] {
    override def additionalLogInfo =
      Map("SubscriptionId" -> subscriptionId, "PaymentDate" -> paymentDate.toString, "NumberOfPeriods" -> numberOfPeriods.toString)

    val date = if (now isBefore paymentDate) paymentDate else now

    val body = {
      <ns1:amend>
        <ns1:requests>
          <ns1:Amendments>
            <ns2:ContractEffectiveDate>{date}</ns2:ContractEffectiveDate>
            <ns2:CustomerAcceptanceDate>{date}</ns2:CustomerAcceptanceDate>
            <ns2:TermType>EVERGREEN</ns2:TermType>
            <ns2:Name>GetSubscriptionDetailsViaAmend</ns2:Name>
            <ns2:SubscriptionId>{subscriptionId}</ns2:SubscriptionId>
            <ns2:Type>TermsAndConditions</ns2:Type>
          </ns1:Amendments>
          <ns1:AmendOptions>
            <ns1:GenerateInvoice>False</ns1:GenerateInvoice>
            <ns1:ProcessPayments>False</ns1:ProcessPayments>
          </ns1:AmendOptions>
          <ns1:PreviewOptions>
            <ns1:EnablePreviewMode>True</ns1:EnablePreviewMode>
            <ns1:NumberOfPeriods>{numberOfPeriods}</ns1:NumberOfPeriods>
          </ns1:PreviewOptions>
        </ns1:requests>
      </ns1:amend>
    }
  }

  case class PreviewInvoicesTillEndOfTermViaAmend(subscriptionId: String, paymentDate: LocalDate = now) extends Action[AmendResult] {
    override def additionalLogInfo = Map("SubscriptionId" -> subscriptionId, "PaymentDate" -> paymentDate.toString)

    val date = if (now isBefore paymentDate) paymentDate else now

    val body = {
      <ns1:amend>
        <ns1:requests>
          <ns1:Amendments>
            <ns2:ContractEffectiveDate>{date}</ns2:ContractEffectiveDate>
            <ns2:CustomerAcceptanceDate>{date}</ns2:CustomerAcceptanceDate>
            <ns2:TermType>TERMED</ns2:TermType>
            <ns2:Name>GetSubscriptionDetailsViaAmend</ns2:Name>
            <ns2:SubscriptionId>{subscriptionId}</ns2:SubscriptionId>
            <ns2:Type>TermsAndConditions</ns2:Type>
          </ns1:Amendments>
          <ns1:AmendOptions>
            <ns1:GenerateInvoice>False</ns1:GenerateInvoice>
            <ns1:ProcessPayments>False</ns1:ProcessPayments>
          </ns1:AmendOptions>
          <ns1:PreviewOptions>
            <ns1:EnablePreviewMode>True</ns1:EnablePreviewMode>
            <ns1:PreviewThroughTermEnd>True</ns1:PreviewThroughTermEnd>
          </ns1:PreviewOptions>
        </ns1:requests>
      </ns1:amend>
    }
  }

  case class CancelPlan(subscriptionId: String, subscriptionRatePlanId: String, date: LocalDate) extends Action[AmendResult] {
    override def additionalLogInfo = Map(
      "SubscriptionId" -> subscriptionId,
      "RatePlanId" -> subscriptionRatePlanId,
      "Date" -> date.toString,
    )

    val body = {
      <ns1:amend>
        <ns1:requests>
          <ns1:Amendments>
            <ns2:EffectiveDate>{date}</ns2:EffectiveDate>
            <ns2:ContractEffectiveDate>{date}</ns2:ContractEffectiveDate>
            <ns2:CustomerAcceptanceDate>{date}</ns2:CustomerAcceptanceDate>
            <ns2:Name>Cancellation</ns2:Name>
            <ns2:RatePlanData>
              <ns1:RatePlan>
                <ns2:AmendmentSubscriptionRatePlanId>{subscriptionRatePlanId}</ns2:AmendmentSubscriptionRatePlanId>
              </ns1:RatePlan>
            </ns2:RatePlanData>
            <ns2:ServiceActivationDate/>
            <ns2:Status>Completed</ns2:Status>
            <ns2:SubscriptionId>{subscriptionId}</ns2:SubscriptionId>
            <ns2:Type>Cancellation</ns2:Type>
          </ns1:Amendments>
        </ns1:requests>
      </ns1:amend>
    }
  }

  case class DowngradePlan(subscriptionId: String, subscriptionRatePlanId: String, newRatePlanId: String, date: LocalDate)
      extends Action[AmendResult] {
    override def additionalLogInfo = Map(
      "SubscriptionId" -> subscriptionId,
      "Existing RatePlanId" -> subscriptionRatePlanId,
      "New RatePlanId" -> newRatePlanId,
      "Date" -> date.toString,
    )

    override val singleTransaction = true

    val body = {
      <ns1:amend>
        <ns1:requests>
          <ns1:Amendments>
            <ns2:ContractEffectiveDate>{date}</ns2:ContractEffectiveDate>
            <ns2:CustomerAcceptanceDate>{date}</ns2:CustomerAcceptanceDate>
            <ns2:Name>Downgrade</ns2:Name>
            <ns2:RatePlanData>
              <ns1:RatePlan>
                <ns2:AmendmentSubscriptionRatePlanId>{subscriptionRatePlanId}</ns2:AmendmentSubscriptionRatePlanId>
              </ns1:RatePlan>
            </ns2:RatePlanData>
            <ns2:ServiceActivationDate/>
            <ns2:Status>Completed</ns2:Status>
            <ns2:SubscriptionId>{subscriptionId}</ns2:SubscriptionId>
            <ns2:Type>RemoveProduct</ns2:Type>
          </ns1:Amendments>
          <ns1:Amendments>
            <ns2:ContractEffectiveDate>{date}</ns2:ContractEffectiveDate>
            <ns2:CustomerAcceptanceDate>{date}</ns2:CustomerAcceptanceDate>
            <ns2:Name>Downgrade</ns2:Name>
            <ns2:RatePlanData>
              <ns1:RatePlan>
                <ns2:ProductRatePlanId>{newRatePlanId}</ns2:ProductRatePlanId>
              </ns1:RatePlan>
            </ns2:RatePlanData>
            <ns2:Status>Completed</ns2:Status>
            <ns2:SubscriptionId>{subscriptionId}</ns2:SubscriptionId>
            <ns2:Type>NewProduct</ns2:Type>
          </ns1:Amendments>
          <ns1:AmendOptions>
            <ns1:GenerateInvoice>false</ns1:GenerateInvoice>
            <ns1:ProcessPayments>false</ns1:ProcessPayments>
          </ns1:AmendOptions>
          <ns1:PreviewOptions>
            <ns1:EnablePreviewMode>false</ns1:EnablePreviewMode>
            <ns1:PreviewThroughTermEnd>true</ns1:PreviewThroughTermEnd>
          </ns1:PreviewOptions>
        </ns1:requests>
      </ns1:amend>
    }
  }

  case class CreateFreeEventUsage(accountId: String, description: String, quantity: Int, subscriptionNumber: String) extends Action[CreateResult] {
    val startDateTime = formatDateTime(DateTime.now)
    override def additionalLogInfo = Map(
      "AccountId" -> accountId,
      "Description" -> description,
      "Quantity" -> quantity.toString,
      "SubscriptionNumber" -> subscriptionNumber,
    )

    override protected val body: Elem =
      <ns1:create>
        <ns1:zObjects xsi:type="ns2:Usage">
          <ns2:AccountId>{accountId}</ns2:AccountId>
          <ns2:SubscriptionNumber>{subscriptionNumber}</ns2:SubscriptionNumber>
          <ns2:Quantity>{quantity}</ns2:Quantity>
          <ns2:StartDateTime>{startDateTime}</ns2:StartDateTime>
          <ns2:Description>{description}</ns2:Description>
          <ns2:UOM>Events</ns2:UOM>
        </ns1:zObjects>
      </ns1:create>
  }

}
