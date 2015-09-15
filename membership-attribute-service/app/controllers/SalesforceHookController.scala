package controllers

import actions.SalesforceSignedAction
import com.google.inject.Inject
import models.ApiErrors._
import parsers.Salesforce.contactReads
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc.BodyParsers.parse
import play.api.mvc.Results.Ok
import play.api.mvc._
import services._

import scala.concurrent.Future
import scala.util.{Success, Try}


class SalesforceHookController @Inject() (attrService: AttributeService) {
  def createAttributes = SalesforceSignedAction {
    Action.async(parse.tolerantText) { request =>
      Try(Json.parse(request.body).as(contactReads)) match {
        case Success(attributes) =>
          attrService.setAttributes(attributes).map(_ => Ok(Json.obj("success" -> true)))
        case _ =>
          Future.successful(badRequest("JSON structure not recognised"))
      }
    }
  }
}
