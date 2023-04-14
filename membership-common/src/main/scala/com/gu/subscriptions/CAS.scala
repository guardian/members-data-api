package com.gu.subscriptions

import play.api.libs.json.{Json, JsPath}

object CAS {
  trait CASResult

  case class CASError(message: String, code: Option[Int]) extends Throwable(s"CAS error - ${code.fold(""){ c => c + ": "}} $message") with CASResult
  case class CASSuccess(expiryType: String, provider: Option[String], expiryDate: String, subscriptionCode: Option[String], content: String) extends CASResult


  object Deserializer {
    implicit val casErrorReads = (JsPath \ "error").read(Json.reads[CASError])
    implicit val casSuccessReads = (JsPath \ "expiry").read(Json.reads[CASSuccess])
  }
}
