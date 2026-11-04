package com.gu.zuora.rest

import scalaz.Applicative

trait AccessTokenProvider[M[_]] {
  def get(): M[String]
}

object AccessTokenProvider {
  def constant[M[_]: Applicative](accessToken: String): AccessTokenProvider[M] = new AccessTokenProvider[M] {
    override def get(): M[String] = Applicative[M].point(accessToken)
  }
}
