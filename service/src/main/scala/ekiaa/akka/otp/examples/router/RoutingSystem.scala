package ekiaa.akka.otp.examples.router

import scala.concurrent.Future

object RoutingSystem {
  case class RouteConversation(cid: String)

  trait CommunicationType

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

  trait AssignmentSettingsRepository {
    def getAssignmentSettings(accountId: String): Future[AssignmentSettings]
  }

}
