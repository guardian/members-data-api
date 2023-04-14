package com.gu.memsub.services

import com.gu.memsub.subsv2.Subscription
import com.gu.memsub.subsv2.SubscriptionPlan._
import com.gu.salesforce.{Contact, SimpleContactRepository}
import com.gu.zuora.rest.ZuoraRestService
import com.gu.zuora.rest.ZuoraRestService.AccountSummary
import scalaz.{-\/, \/-}

import scala.concurrent.{ExecutionContext, Future}

object SalesforceContactServiceUsingZuoraRest {

  def getPersonContactForAccountId(salesforceAccountId: String)(implicit simpleContactRepository: SimpleContactRepository, ctx: ExecutionContext): Future[Contact] = {
    simpleContactRepository.getByAccountId(salesforceAccountId).map {
      case \/-(contact) => contact
      case -\/(error) => throw new IllegalStateException(s"Error retrieving Salesforce Person Contact for Account ${salesforceAccountId}: $error")
    }
  }

  def getContactById(salesforceContactId: String)(implicit simpleContactRepository: SimpleContactRepository, ctx: ExecutionContext): Future[Contact] = {
    simpleContactRepository.getByContactId(salesforceContactId).map {
      case \/-(contact) => contact
      case -\/(error) => throw new IllegalStateException(s"Error retrieving Salesforce Contact for $salesforceContactId: $error")
    }
  }

  def zuoraAccountFromSub(subscription: Subscription[AnyPlan])(implicit zuoraRestService: ZuoraRestService[Future], simpleContactRepository: SimpleContactRepository, ctx: ExecutionContext): Future[AccountSummary] = {
    zuoraRestService.getAccount(subscription.accountId).map {
      case \/-(zuoraAccount) => zuoraAccount
      case -\/(error) => throw new IllegalStateException(s"Error retrieving Zuora account ${subscription.accountId}: $error")
    }
  }

  def getBuyerContactForZuoraAccount(zuoraAccount: AccountSummary)(implicit simpleContactRepository: SimpleContactRepository, ctx: ExecutionContext): Future[Contact] = {
    for {
      recipientContact <- getRecipientContactForZuoraAccount(zuoraAccount) // Zuora's accountSummary does not contain the CRM ID :(
      salesforceAccountId = recipientContact.salesforceAccountId // so we have to use the recipient's account ID which == Zuora's CRM ID.
      buyerContact <- getPersonContactForAccountId(salesforceAccountId)
    } yield buyerContact
  }

  def getRecipientContactForZuoraAccount(zuoraAccount: AccountSummary)(implicit simpleContactRepository: SimpleContactRepository, ctx: ExecutionContext): Future[Contact] = {
    for {
      sfContactId <- Some(zuoraAccount.sfContactId.get).filter(_.nonEmpty).fold[Future[String]](Future.failed(new IllegalStateException(s"Zuora record for ${zuoraAccount.id} has no sfContactId")))(Future.successful)
      recipientContact <- getContactById(sfContactId)
    } yield recipientContact
  }

  def getBuyerContactForSubscription(subscription: Subscription[AnyPlan])(implicit zuoraRestService: ZuoraRestService[Future], simpleContactRepository: SimpleContactRepository, ctx: ExecutionContext): Future[Contact] = {
    for {
      zuoraAccount <- zuoraAccountFromSub(subscription)
      buyerContact <- getBuyerContactForZuoraAccount(zuoraAccount)
    } yield buyerContact
  }

}
