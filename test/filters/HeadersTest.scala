package filters

import org.specs2.mutable.Specification
import org.specs2.mock.Mockito
import Headers._
import play.api.mvc
import org.specs2.specification.Scope

class HeadersTest extends Specification with Mockito {
  trait fixtures extends Scope {
    val headersFixture = mock[mvc.Headers]
    headersFixture.get("X-Forwarded-For") returns Some("86.142.23.56, 78.24.67.23")

    val requestHeaderFixture = mock[mvc.RequestHeader]
    requestHeaderFixture.remoteAddress returns "127.0.0.1"
    requestHeaderFixture.headers returns headersFixture
  }

  "forwardedFor" should {
    "return the X-Forwarded-For as a list of IPs" in new fixtures {
      headersFixture.forwardedFor mustEqual Some(List("86.142.23.56", "78.24.67.23"))
    }
  }

  "realRemoteAddr" should {
    "return the first X-Forwarded-For IP if present" in new fixtures {
      requestHeaderFixture.realRemoteAddr mustEqual "86.142.23.56"
    }

    "return the Remote Addr header if X-Forwarded-For is not present" in new fixtures {
      headersFixture.get("X-Forwarded-For") returns None

      requestHeaderFixture.realRemoteAddr mustEqual "127.0.0.1"
    }
  }
}
