package ekiaa.akka.otp.examples.router

import scala.concurrent.Future

object RoutingSystem {
  case class RouteConversation(cid: String)

  trait CommunicationType

  object CommunicationType {
    case object ChatSite extends CommunicationType
  }

  trait ConversationEvent {
    def cid: String
    def initialCommunicationType: CommunicationType
    def selectedGroupId: String
    def employeeId: Option[String]
    def voiceOperators: Set[String]
  }

  trait ConversationRepository {
    def getConversation(cid: String): Future[ConversationEvent]
  }

  trait AssignmentSettings

  object AssignmentSettings {
    case object Random extends AssignmentSettings
    case object Fair extends AssignmentSettings
  }

  trait AssignmentSettingsRepository {
    def getAssignmentSettings(accountId: String): Future[AssignmentSettings]
  }

  case class EmployeeLimits(chatSiteLimit: Option[Long], otherChannels: Option[Long])

  trait EmployeeLimitsRepository {
    def getEmployeeLimits(accountId: String, employeeIds: Set[String]): Future[Map[String, EmployeeLimits]]
  }

  trait EmployeeStatus

  object EmployeeStatus {
    case object Online extends EmployeeStatus
    case object Offline extends EmployeeStatus
  }

}
