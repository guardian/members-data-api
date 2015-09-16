package services

import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64

import configuration.Config
import play.api.Play
import play.api.Play.current

import scala.util.{Success, Try}

object SalesforceCertificateSignatureChecker extends SalesforceSignatureChecker{
  lazy val salesforceCert = {
    val resource = Play.resourceAsStream(Config.salesforceCert).get
    CertificateFactory.getInstance("X.509").generateCertificate(resource)
  }

  override def check(payload: String)(signatureBase64: String): SalesforceSignatureCheck = {
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
