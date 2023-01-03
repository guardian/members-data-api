package controllers

import actions.{AuthAndBackendRequest, CommonActions, Return401IfNotSignedInRecently}
import com.gu.salesforce.{ContactRepository, SFContactId, SimpleContactRepository}
import com.typesafe.scalalogging.LazyLogging
import models.DeliveryAddress
import monitoring.CreateMetrics
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.Exception

class ContactController(
    commonActions: CommonActions,
    override val controllerComponents: ControllerComponents,
    createMetrics: CreateMetrics,
) extends BaseController
    with LazyLogging {

  import commonActions._

  private implicit val ec: ExecutionContext = controllerComponents.executionContext
  val metrics = createMetrics.forService(classOf[ContactController])

  def updateDeliveryAddress(contactId: String): Action[AnyContent] =
    AuthAndBackendViaIdapiAction(Return401IfNotSignedInRecently).async { request =>
      metrics.measureDuration("PUT /user-attributes/me/delivery-address/:contactId") {
        logger.info(s"Updating delivery address for contact $contactId")

        isContactOwnedByRequester(request, contactId) flatMap { valid =>
          if (valid) {
            val submitted = Exception.allCatch.either {
              request.body.asJson map (_.as[DeliveryAddress])
            }

            submitted match {
              case Left(parsingFailure) => Future.successful(BadRequest(parsingFailure.getMessage))
              case Right(None) => Future.successful(BadRequest(s"Not json: ${request.body}"))
              case Right(Some(address)) =>
                val contactRepo = request.touchpoint.contactRepo
                update(contactRepo, contactId, address) map { _ =>
                  NoContent
                } recover { case updateFailure =>
                  BadGateway(updateFailure.getMessage)
                }
            }
          } else
            Future.successful(
              BadRequest(
                s"Contact $contactId not related to current user ${request.redirectAdvice.userId}",
              ),
            )
        }
      }
    }

  private def isContactOwnedByRequester(
      request: AuthAndBackendRequest[AnyContent],
      contactId: String,
  ): Future[Boolean] = {
    val contactRepo = request.touchpoint.contactRepo
    request.redirectAdvice.userId match {
      case Some(userId) =>
        contactRepo.get(userId).map(_.toEither).map {
          case Right(Some(contact)) => contact.salesforceContactId == contactId
          case _ => false
        }
      case None => Future.successful(false)
    }
  }

  private def update(
      contactRepo: ContactRepository,
      contactId: String,
      address: DeliveryAddress,
  ): Future[Unit] = {
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
        contactField("MailingCountry", address.country) ++
        contactField("Delivery_Information__c", address.instructions) ++
        contactField("Address_Change_Information_Last_Quoted__c", address.addressChangeInformation)
    }
    contactRepo.salesforce.Contact.update(SFContactId(contactId), contactFields)
  }
}
