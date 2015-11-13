package framework

import play.api.BuiltInComponents
import play.api.inject.Injector

/*
 * Takes some BuiltInComponents and allows you to mix in more *Components traits
 * which will then use the same instances of the components as those in BuiltInComponents
 */
class ClonedComponents(components: BuiltInComponents) extends BuiltInComponents {

  def environment = components.environment
  def sourceMapper = components.sourceMapper
  def webCommands = components.webCommands
  def configuration = components.configuration
  def router = components.router

  override lazy val injector: Injector = components.injector
  override lazy val httpConfiguration = components.httpConfiguration
  override lazy val httpRequestHandler = components.httpRequestHandler
  override lazy val httpErrorHandler = components.httpErrorHandler
  override lazy val httpFilters = components.httpFilters

  override lazy val applicationLifecycle = components.applicationLifecycle
  override lazy val application = components.application
  override lazy val actorSystem = components.actorSystem

  override lazy val cryptoConfig = components.cryptoConfig
  override lazy val crypto = components.crypto
}