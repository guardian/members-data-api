package com.gu.salesforce

import com.typesafe.config.Config

trait ContactRecordType {
  def name: String
}
case object StandardCustomer extends ContactRecordType {
  val name = "Standard Customer"
}
case object DeliveryRecipientContact extends ContactRecordType {
  val name = "Delivery / Recipient Contact"

}
class ContactRecordTypes(config: Config) {

  /* Does a boot time check to ensure getIdForContactRecordType is safe in-ife */
  private val standardCustomerId = config.getString("standard-customer")
  private val deliveryRecipientId = config.getString("delivery-recipient")

  def getIdForContactRecordType(recordType: ContactRecordType): String = {
    recordType match {
      case StandardCustomer => standardCustomerId
      case DeliveryRecipientContact => deliveryRecipientId
    }
  }
}
