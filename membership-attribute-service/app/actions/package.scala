import com.gu.identity.play.IdMinimalUser
import play.api.mvc.Security.AuthenticatedRequest

package object actions {

  type AuthRequest[A] = AuthenticatedRequest[A, IdMinimalUser]

}
