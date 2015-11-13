package components

import com.softwaremill.macwire._
import controllers.{AttributeController, HealthCheckController, SalesforceHookController}

trait ControllerComponents { self: TouchpointComponents =>
  lazy val attributeController = wire[AttributeController]
  lazy val healthCheckController = wire[HealthCheckController]
  lazy val salesForceController = wire[SalesforceHookController]
}
