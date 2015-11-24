package framework

import play.api._
import play.api.cache.EhCacheComponents
import play.api.i18n._
import play.api.libs.openid._
import play.api.libs.ws.ning.NingWSComponents
import play.filters.cors.CORSComponents
import play.filters.csrf.CSRFComponents
import play.filters.gzip.GzipFilterComponents
import play.filters.headers.SecurityHeadersComponents

trait AllComponentTraits extends
      BuiltInComponents with
      EhCacheComponents with
      I18nComponents with
      OpenIDComponents with
      NingWSComponents with
      CORSComponents with
      CSRFComponents with
      GzipFilterComponents with
      SecurityHeadersComponents {}

class AllComponents(components: AllComponentTraits) extends AllComponentTraits {

  //BuiltInComponents
  override def environment = components.environment
  override def sourceMapper = components.sourceMapper
  override def webCommands = components.webCommands
  override def configuration = components.configuration
  override def router = components.router
  override lazy val injector = components.injector
  override lazy val httpConfiguration = components.httpConfiguration
  override lazy val httpRequestHandler = components.httpRequestHandler
  override lazy val httpErrorHandler = components.httpErrorHandler
  override lazy val httpFilters = components.httpFilters
  override lazy val applicationLifecycle = components.applicationLifecycle
  override lazy val application = components.application
  override lazy val actorSystem = components.actorSystem
  override lazy val cryptoConfig = components.cryptoConfig
  override lazy val crypto = components.crypto
  
  //EhCacheComponents
  override lazy val ehCacheManager = components.ehCacheManager
  override def cacheApi(name: String) = components.cacheApi(name)
  override lazy val defaultCacheApi = components.defaultCacheApi

  //I18nComponents
  override lazy val messagesApi = components.messagesApi
  override lazy val langs = components.langs
  
  //OpenIDComponents
  override lazy val openIdDiscovery = components.openIdDiscovery
  override lazy val openIdClient = components.openIdClient
  
  //NingWSComponents
  override lazy val wsClientConfig = components.wsClientConfig
  override lazy val ningWsClientConfig = components.ningWsClientConfig
  override lazy val wsApi = components.wsApi
  override lazy val wsClient = components.wsClient
  
  //CORSComponents
  override lazy val corsConfig = components.corsConfig
  override lazy val corsFilter = components.corsFilter
  override lazy val corsPathPrefixes = components.corsPathPrefixes
  
  //CSRFComponents
  override lazy val csrfConfig = components.csrfConfig
  override lazy val csrfTokenProvider = components.csrfTokenProvider
  override lazy val csrfErrorHandler = components.csrfErrorHandler
  override lazy val csrfFilter = components.csrfFilter

  //GZIPComponents
  override lazy val gzipFilterConfig = components.gzipFilterConfig
  override lazy val gzipFilter = components.gzipFilter

  //SecurityHeadersComponents
  override lazy val securityHeadersConfig = components.securityHeadersConfig
  override lazy val securityHeadersFilter = components.securityHeadersFilter
}
