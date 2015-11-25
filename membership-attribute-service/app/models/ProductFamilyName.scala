package models

sealed trait ProductFamilyName
case object DigitalPack extends ProductFamilyName
case object Membership extends ProductFamilyName