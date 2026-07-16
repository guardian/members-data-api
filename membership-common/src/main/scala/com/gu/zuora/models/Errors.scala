package com.gu.zuora.models.errors

sealed trait Error extends Throwable {
  val message: String
  override def getMessage: String = message
}

case class QueryError(msg: String) extends Error {
  override val message = msg
}
