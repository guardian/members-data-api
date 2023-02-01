package models.subscription

import com.gu.i18n.Title

trait FullName {
  def first: String
  def last: String
  def title: Option[Title]
}
