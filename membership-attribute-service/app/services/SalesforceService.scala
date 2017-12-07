package services

import com.gu.salesforce.ContactRepository

class SalesforceService(contactRepository: ContactRepository) extends HealthCheckableService {
  override def checkHealth: Boolean = contactRepository.salesforce.isAuthenticated
}
