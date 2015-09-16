package services

import java.security.cert.Certificate

sealed trait SalesforceSignatureCheck
case object CheckSuccessful extends SalesforceSignatureCheck
case object FormatError extends SalesforceSignatureCheck
case object WrongSignature extends SalesforceSignatureCheck

trait SalesforceSignatureChecker {
  def check(payload: String)(signature: String)(implicit salesforceCert: Certificate): SalesforceSignatureCheck
}
