package services

import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64

import configuration.Config
import play.api.Play

import scala.util.{Success, Try}

sealed trait SalesforceSignatureCheck
case object CheckSuccessful extends SalesforceSignatureCheck
case object FormatError extends SalesforceSignatureCheck
case object WrongSignature extends SalesforceSignatureCheck
import play.api.Play.current

object SalesforceSignatureChecker {
  lazy val salesforceCert = {
    val resource = Play.resourceAsStream(Config.salesforceCert).get
    CertificateFactory.getInstance("X.509").generateCertificate(resource)
  }

  def check(payload: String)(signatureBase64: String): SalesforceSignatureCheck = {
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