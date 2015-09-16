package actions

import models.ApiError._
import models.ApiErrors._
import play.api.mvc.BodyParsers.parse
import play.api.mvc.{Action, BodyParser, Request}
import services.{CheckSuccessful, FormatError, SalesforceSignatureChecker, WrongSignature}

import scala.concurrent.Future

case class SalesforceSignedAction(action: Action[String])(implicit signatureChecker: SalesforceSignatureChecker) extends Action[String] {
  val salesforceSignatureHeader = "X-SALESFORCE-SIGNATURE"
  override def apply(request: Request[String]) = {
    val headerSignature = request.headers.get(salesforceSignatureHeader)
    val signatureCheck = headerSignature.map(signatureChecker.check(request.body))

    signatureCheck match {
      case Some(CheckSuccessful) =>
        action(request)
      case Some(WrongSignature) =>
        Future.successful(unauthorized("The signature is invalid"))
      case Some(FormatError) =>
        Future.successful(badRequest("Make sure that the signature header is base64-encoded and that the body is a valid UTF-8 string"))
      case None =>
        Future.successful(unauthorized("Missing a X-SALESFORCE-SIGNATURE header"))
    }
  }

  override def parser: BodyParser[String] = parse.tolerantText
}
