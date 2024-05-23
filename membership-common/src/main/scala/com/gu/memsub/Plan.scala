package com.gu.memsub

sealed trait Status {
  def name: String
}
object Status {
  case object Legacy extends Status {
    override val name: String = "legacy"
  }
  case object Current extends Status {
    override val name: String = "current"
  }
  case object Upcoming extends Status {
    override val name: String = "upcoming"
  }

}
