package com.gu.memsub.promo

import com.typesafe.config.Config

object DynamoTables {

  def promotions(config: Config, stage: String): String =
    config.getString(s"touchpoint.backend.environments.$stage.dynamodb.promotions")

  def campaigns(config: Config, stage: String): String =
    config.getString(s"touchpoint.backend.environments.$stage.dynamodb.campaigns")

}
