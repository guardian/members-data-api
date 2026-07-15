package com.gu.zuora.models

import com.gu.zuora.models.Queries.PreviewInvoiceItem

trait Result
object Results {
  case class Authentication(token: String, url: String) extends Result
  case class QueryResult(results: Seq[Map[String, String]]) extends Result
  case class UpdateResult(id: String) extends Result
  case class SubscribeResult(subscriptionId: String, subscriptionName: String, accountId: String) extends Result
  case class AmendResult(ids: Seq[String], invoiceItems: Seq[PreviewInvoiceItem]) extends Result
  case class CreateResult(id: String) extends Result
}
