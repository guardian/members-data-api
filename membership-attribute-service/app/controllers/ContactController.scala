package controllers

import actions.CommonActions
import com.gu.salesforce.SFContactId
import models.DeliveryAddress
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.Exception

class ContactController(
    commonActions: CommonActions,
    override val controllerComponents: ControllerComponents
) extends BaseController {

  import commonActions._

  private implicit val ec: ExecutionContext = controllerComponents.executionContext

  def updateDeliveryAddress(contactId: String): Action[AnyContent] =
    AuthAndBackendViaAuthLibAction.async { request =>
      val contactRepo = request.touchpoint.contactRepo

      val submitted = Exception.allCatch.either {
        request.body.asJson map (_.as[DeliveryAddress])
      }

      submitted match {
        case Left(parsingFailure) => Future.successful(BadRequest(parsingFailure.getMessage))
        case Right(None)          => Future.successful(BadRequest(s"Not json: ${request.body}"))
        case Right(Some(address)) =>

          val contactFields = {
            def contactField(name: String, optValue: Option[String]): Map[String, String] =
              optValue map { value =>
                Map(name -> value.trim)
              } getOrElse Map()
            val mergedAddressLines = DeliveryAddress.mergeAddressLines(address)
            Map() ++
              contactField("MailingStreet", mergedAddressLines) ++
              contactField("MailingCity", address.town) ++
              contactField("MailingState", address.region) ++
              contactField("MailingPostalCode", address.postcode) ++
              contactField("MailingCountry", address.country)
          }

          val update = contactRepo.salesforce.Contact.update(SFContactId(contactId), contactFields)

          update map { _ =>
            NoContent
          } recover {
            case updateFailure => BadGateway(updateFailure.getMessage)
          }
      }
    }
}
