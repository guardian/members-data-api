package actions

import actions.Functions._
import controllers._

trait CommonActions {
  val NoCacheAction = resultModifier(NoCache(_))
  val CachedAction = resultModifier(Cached(_))
  val AuthenticatedAction = NoCacheAction andThen authenticated()
}
