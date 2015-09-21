package services

import org.specs2.mutable.Specification

class SalesforceCertificateSignatureCheckerTest extends Specification {
  // the string "abc" signed by the key corresponding to salesforce-test.crt
  implicit val salesforceCert = fakes.salesforceCert

  "it checks the validity if the signature" should {
    "returns a successful response when the signature is valid" in {
      // the string "abc" signed by the key corresponding to salesforce-test.crt
      val signature = "Rnr3YG0OtMA7jhuMgHnhKpHssd15z9umraG5gG+mVVPPKFnyLaaHpOV2wXgXlHVMJNhKXnUuhi+92icXPZBRALREqvaRpqDUdyNS+WwjYxQgnyRKNYqJpUnkek/9Q8MW5oXCi53EB7hjVDAtd8/kt7zx1E0hYZt1dzaGzQ9xkBhF5mF+4ISKSPHHNiq1CHV6ipifLDRA9DpLEYvuUZB4B2kVnDXFVBDjnLKL/ZRl+2afQOm4/JHJVG3VZ10NA907X7f14cmMY6voIdgCTXvcgkRRy6WieBIdKFy7ihXNz9tNodBL299jZSinIyOCiLiq95LPfeO/xBAO12RVEIAfqQ=="
      SalesforceCertificateSignatureChecker.check("abc")(signature) mustEqual CheckSuccessful
    }

    "notifies a wrong format when the signature format is invalid" in {
      SalesforceCertificateSignatureChecker.check("abc")("wrong format") mustEqual FormatError
    }

    "fails when the signature is of the right format but wrong" in {
      // the string "def" signed by the key corresponding to salesforce-test.crt
      val signature = "RspiB66abYiu9UfleeV1B6fsA87clP8RiuaBZM2Djf9t9by8mf/hVua435eqdhAyi/gVBNVB6O2SYQTcGS4BwsLqKUWvuANPLBI6HHQWLA+UxSpWFdBp0UmRFfVCAlKdTaut9fMxOeItAqlyflrwLlQlNNf1McVI3VP3biU5SLX0UjBShPv08OwIsx/piGzGXegE46ctUy/ubX9tVcCfcNIGdthQCSKUbhCHDVHb5yWnDuNgYBuo+wtV8CrgHlcJk4JzBlOPrUECDXOR4mjLlhmDqKgQWtQImSz7nbhj2eRzfuqfxfXIAsImRzg9+/01rKXBVO5vDFwwLcWfz18lMg=="
      SalesforceCertificateSignatureChecker.check("abc")(signature) mustEqual WrongSignature
    }

  }
}
