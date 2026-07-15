package com.gu.zuora.models.errors

sealed trait Error extends Throwable {
  val message: String
}

case class QueryError(msg: String) extends Error {
  override val message = msg
}
