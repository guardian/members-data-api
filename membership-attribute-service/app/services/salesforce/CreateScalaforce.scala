package services.salesforce

import akka.actor.Scheduler
import utils.RequestRunners
import monitoring.{CreateMetrics, SalesforceMetrics}

import scala.concurrent.ExecutionContext

object CreateScalaforce {
  def apply(salesforceConfig: SalesforceConfig, scheduler: Scheduler, appName: String, createMetrics: CreateMetrics)(implicit
      executionContext: ExecutionContext,
  ): Scalaforce = {
    val salesforce: Scalaforce = new Scalaforce(
      appName,
      salesforceConfig.envName,
      salesforceConfig,
      RequestRunners.futureRunner,
      scheduler,
      createMetrics,
    )
    salesforce.startAuth()
    salesforce
  }
}
