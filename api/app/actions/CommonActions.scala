package actions

import actions.Functions._
import controllers._
import play.api.mvc.{Result, Request, ActionBuilder}
import services.AuthenticationService
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future

trait CommonActions {

  val NoCacheAction = resultModifier(NoCache(_))

  val CachedAction = resultModifier(Cached(_))

  val AuthenticatedAction = NoCacheAction andThen authenticated()

}
