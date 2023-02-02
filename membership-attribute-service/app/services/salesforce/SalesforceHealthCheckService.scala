package services.salesforce

import services.HealthCheckableService

class SalesforceHealthCheckService(salesforce: Scalaforce) extends HealthCheckableService {
  override def checkHealth: Boolean = salesforce.isAuthenticated
}
