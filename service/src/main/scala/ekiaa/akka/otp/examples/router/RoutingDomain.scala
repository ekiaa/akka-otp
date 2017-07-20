package ekiaa.akka.otp.examples.router

import ekiaa.akka.otp.examples.router.RoutingSystem.{AssignmentSettings, CommunicationType, EmployeeLimits, EmployeeStatus}

object RoutingDomain {

  type QueueId = String
  type GroupId = String
  type EmployeeId = String
  type ConversationId = String

  case class Conversation(cid: ConversationId,
                          initialCommunicationType: CommunicationType,
                          selectedGroup: GroupId,
                          employeeId: Option[EmployeeId],
                          voiceOperators: Set[EmployeeId])

  case class BusySlots(chatSite: Option[Long], otherChannels: Option[Long])

  case class Employee(employeeId: EmployeeId,
                      status: EmployeeStatus,
                      limits: EmployeeLimits,
                      busySlots: BusySlots,
                      conversations: Set[Conversation])

  case class Queue(queueId: QueueId,
                   conversations: Set[Conversation],
                   employees: Set[EmployeeId]) {
    def enqueueConversation(conversation: Conversation): Queue = {
      copy(
        conversations = conversations + conversation
      )
    }
  }

}
