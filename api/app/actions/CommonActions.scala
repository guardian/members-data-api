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

  val AddUserInfoToResponse = new ActionBuilder[Request] {
    def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]) = {
      block(request).map { result =>
        (for (user <- AuthenticationService.authenticatedUserFor(request)) yield {
          result.withHeaders("X-Gu-Identity-Id" -> user.id)
        }).getOrElse(result)
      }
    }
  }

}
