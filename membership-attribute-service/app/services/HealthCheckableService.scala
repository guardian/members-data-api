package services

trait HealthCheckableService {
  def serviceName: String = this.getClass.getSimpleName
  def checkHealth: Boolean
}
