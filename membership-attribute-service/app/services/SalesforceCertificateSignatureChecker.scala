package services

import java.security.Signature
import java.security.cert.Certificate
import java.util.Base64

import scala.util.{Success, Try}

object SalesforceCertificateSignatureChecker extends SalesforceSignatureChecker{
  override def check(payload: String)(signatureBase64: String)(implicit salesforceCert: Certificate): SalesforceSignatureCheck = {
    val pubKey = salesforceCert.getPublicKey
    val checker = Signature.getInstance("SHA256withRSA")
    checker.initVerify(pubKey)

    Try {
      val signature = Base64.getDecoder.decode(signatureBase64.getBytes)
      checker.update(payload.getBytes)
      checker.verify(signature)
    } match {
      case Success(true) => CheckSuccessful
      case Success(false) => WrongSignature
      case _ => FormatError
    }
  }
}
