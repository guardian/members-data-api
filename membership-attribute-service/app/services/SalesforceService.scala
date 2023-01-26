package services

import com.gu.salesforce.Scalaforce

class SalesforceService(salesforce: Scalaforce) extends HealthCheckableService {
  override def checkHealth: Boolean = salesforce.isAuthenticated
}
