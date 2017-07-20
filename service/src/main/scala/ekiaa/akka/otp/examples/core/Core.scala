package ekiaa.akka.otp.examples.core

import scala.collection.immutable.HashMap

object Core {

  type ConversationId = String
  type EmployeeId = String
  type QueueId = String
  type GroupId = String

  case class Conversation(id: ConversationId,
                          groupId: GroupId,
                          assignedEmployee: Option[EmployeeId])

  trait EmployeeStatus
  case object Online extends EmployeeStatus
  case object Offline extends EmployeeStatus

  case class Employee(id: EmployeeId,
                      assignedConversations: Seq[ConversationId],
                      employeeStatus: EmployeeStatus)

  case class Queue(id: QueueId,
                   routedConversations: Seq[ConversationId])

  case class Group(id: GroupId,
                   employees: Seq[EmployeeId])

  case class State(conversations: HashMap[ConversationId, Conversation],
                   employees: HashMap[EmployeeId, Employee],
                   queues: HashMap[QueueId, Queue],
                   groups: HashMap[GroupId, Group],
                   queueByGroup: HashMap[GroupId, QueueId])

  trait Repository {

    def getConversation(conversationId: ConversationId, state: State): Option[Conversation] = {
      state.conversations.get(conversationId)
    }

    def getEmployee(employeeId: EmployeeId, state: State): Option[Employee] = {
      state.employees.get(employeeId)
    }

    def saveEmployee(employee: Employee, state: State): State = {
      state.copy(employees = state.employees + (employee.id -> employee))
    }

    def getEmployees(state: State): Seq[Employee] = {
      state.employees.values.toSeq
    }

    def getQueue(queueId: QueueId, state: State): Option[Queue] = {
      state.queues.get(queueId)
    }

    def saveQueue(queue: Queue, state: State): State = {
      state.copy(queues = state.queues + (queue.id -> queue))
    }

    def getGroup(groupId: GroupId, state: State): Option[Group] = {
      state.groups.get(groupId)
    }

    def getQueueByGroup(groupId: GroupId, state: State): Option[Queue] = {
      state.queueByGroup.get(groupId).flatMap(queueId => getQueue(queueId, state))
    }

  }

  trait EmployeeService {
    def assignConversation(conversationId: ConversationId, employee: Employee): Employee = {
      employee.copy(assignedConversations = employee.assignedConversations :+ conversationId)
    }
  }

  trait QueueService {
    def enqueueConversation(conversationId: ConversationId, queue: Queue): Queue = {
      queue.copy(routedConversations = queue.routedConversations :+ conversationId)
    }
  }

  trait Routing extends Repository with EmployeeService with QueueService {

    def routeConversation(conversationId: ConversationId, state: State): State = {
      for {
        conversation <- getConversation(conversationId, state)
        queue <- getQueueByGroup(conversation.groupId, state)
      } yield {
        getGroup(conversation.groupId, state).
          flatMap(group => group.employees.flatMap(employeeId => getEmployee(employeeId, state)).find(employee => employee.employeeStatus == Online)).
          map(employee => assignConversation(conversationId, employee)).
          map(employee => saveEmployee(employee, state)).
          getOrElse(saveQueue(enqueueConversation(conversationId, queue), state))
      }
    }

  }

}
