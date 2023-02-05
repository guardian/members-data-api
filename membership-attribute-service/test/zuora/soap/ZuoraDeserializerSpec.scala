package zuora.soap

import org.specs2.matcher.Matchers
import org.specs2.mutable.Specification
import services.zuora.soap.Readers
import services.zuora.soap.models.Results.UpdateResult
import services.zuora.soap.models.errors
import services.zuora.soap.models.errors.{InvalidValue, ZuoraPartialError}

class ZuoraDeserializerSpec extends Specification with Matchers {
  "An Update can be deserialized" should {
    "into a valid UpdateResult" in {
      val validResponse =
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
          <soapenv:Body>
            <ns1:updateResponse xmlns:ns1="http://api.zuora.com/">
              <ns1:result>
                <ns1:Id>2c92c0f94ed8d0d7014ef90424654cfc</ns1:Id>
                <ns1:Success>true</ns1:Success>
              </ns1:result>
            </ns1:updateResponse>
          </soapenv:Body>
        </soapenv:Envelope>

      Readers.updateResultReader.read(validResponse.toString()) shouldEqual Right(UpdateResult("2c92c0f94ed8d0d7014ef90424654cfc"))
    }

    "into a Failure" in {
      val invalidResponse =
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
          <soapenv:Body>
            <ns1:updateResponse xmlns:ns1="http://api.zuora.com/">
              <ns1:result>
                <ns1:Errors>
                  <ns1:Code>INVALID_VALUE</ns1:Code>
                  <ns1:Message>The length of field value is too big.</ns1:Message>
                </ns1:Errors>
                <ns1:Success>false</ns1:Success>
              </ns1:result>
            </ns1:updateResponse>
          </soapenv:Body>
        </soapenv:Envelope>

      val error = ZuoraPartialError("INVALID_VALUE", "The length of field value is too big.", InvalidValue)

      Readers.updateResultReader.read(invalidResponse.toString()) shouldEqual Left(error)
    }
  }
}
