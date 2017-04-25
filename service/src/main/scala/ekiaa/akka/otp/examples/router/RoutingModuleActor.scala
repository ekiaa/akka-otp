package ekiaa.akka.otp.examples.router

import java.util.UUID

import RoutingSystem._
import akka.pattern.pipe
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import com.typesafe.scalalogging.StrictLogging

class RoutingModuleActor(accountId: String,
                         conversationRepository: ConversationRepository,
                         assignmentSettingsRepository: AssignmentSettingsRepository,
                         employeeLimitsRepository: EmployeeLimitsRepository
                        ) extends PersistentActor with StrictLogging {
  import RoutingModuleActor._

  override def persistenceId: String = s"Router-$accountId"

  object RecoveringState {
    private var state: PersistedState = PersistedState()
    def apply(snapshot: PersistedState): Unit =
      state = snapshot
    def applyEvent(event: Event): Unit =
      state = PersistedState.applyEvent(DomainEvent(event, state))
    def recoveredState: PersistedState =
      state
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot: PersistedState) =>
      RecoveringState.apply(snapshot)
    case event: Event =>
      RecoveringState.applyEvent(event)
    case RecoveryCompleted =>
      perepareOperatingState(RecoveringState.recoveredState)
  }

  override def receiveCommand: Receive = ???

  private def perepareOperatingState(persistedState: PersistedState): Unit = {
    requestAssignmentSettings(
      InitializationState(persistedState = persistedState)
    )
  }

  private def requestAssignmentSettings(initializationState: InitializationState): Unit = {
    pipe(assignmentSettingsRepository.getAssignmentSettings(accountId)) to self
    context.become(
      assignmentSettingsRequested(initializationState)
    )
  }

  private def assignmentSettingsRequested(initializationState: InitializationState): Receive = {
    case assignmentSettings: AssignmentSettings =>
      requestEmployeeLimits(
        initializationState.copy(
          assignmentSettings = Some(assignmentSettings)
        )
      )
    case command: RouteConversation =>
      persist(ConversationRoutingRequested(command.cid)) { _ =>
        context.become(
          assignmentSettingsRequested(initializationState.copy(
            persistedState = initializationState.persistedState.copy(
              waitedProcessingCids = initializationState.persistedState.waitedProcessingCids + command.cid
            )
          ))
        )
      }
    case _ =>
      stash()
  }

  private def requestEmployeeLimits(initializationState: InitializationState): Unit = {
    val employeeIds = initializationState.persistedState.employees.values.map(_.employeeId).toSet
    pipe(employeeLimitsRepository.getEmployeeLimits(accountId, employeeIds)) to self
    context.become(
      employeeLimitsRequested(initializationState)
    )
  }

  private def employeeLimitsRequested(initializationState: InitializationState): Receive = {
    case employeeLimitsMap: Map[EmployeeId, EmployeeLimits] =>
      constructOperatingState(
        initializationState.copy(
          employeeLimitsMap = employeeLimitsMap
        )
      )
    case command: RouteConversation =>
      persist(ConversationRoutingRequested(command.cid)) { _ =>
        context.become(
          assignmentSettingsRequested(initializationState.copy(
            persistedState = initializationState.persistedState.copy(
              waitedProcessingCids = initializationState.persistedState.waitedProcessingCids + command.cid
            )
          ))
        )
      }
    case _ =>
      stash()
  }

  private def constructOperatingState(initializationState: InitializationState): Unit = {
    val state = State(
      waitedProcessingCids = initializationState.persistedState.waitedProcessingCids,
      queueIdByGroupId = initializationState.persistedState.queueIdByGroupId,
      queueIdByEmployeeId = initializationState.persistedState.queueIdByEmployeeId,
      queueIdByConversationId = initializationState.persistedState.queueIdByConversationId,
      queues = initializationState.persistedState.queues.map { case (queueId, persistedQueue) =>
        queueId -> Queue(
          queueId = persistedQueue.queueId,
          conversations = persistedQueue.conversations,
          employees = persistedQueue.employees
        )
      },
      employees = initializationState.persistedState.employees.map { case (employeeId, persistedEmployee) =>
        val employeeLimits = initializationState.employeeLimitsMap.
          getOrElse(employeeId, EmployeeLimits(chatSiteLimit = None, otherChannels = None))
        val conversations = persistedEmployee.conversations
        val chatSiteBusySlots = employeeLimits.chatSiteLimit map { _ =>
          conversations.count(c => c.initialCommunicationType == CommunicationType.ChatSite).toLong
        }
        val otherChannelsBusySlots = employeeLimits.otherChannels map { _ =>
          conversations.count(c => c.initialCommunicationType != CommunicationType.ChatSite).toLong
        }
        employeeId -> Employee(
          employeeId = persistedEmployee.employeeId,
          status = EmployeeStatus.Offline,
          limits = employeeLimits,
          busySlots = BusySlots(chatSite = chatSiteBusySlots, otherChannels = otherChannelsBusySlots),
          conversations = conversations
        )
      },
      assignmentSettings = initializationState.assignmentSettings.get
    )
    processNextRoutingRequest(state)
  }

  private def processNextRoutingRequest(state: State): Unit = {
    state.waitedProcessingCids.headOption match {
      case Some(cid) =>
        pipe(conversationRepository.getConversation(cid)) to self
        context.become(
          conversationEventRequested(cid,
            state.copy(
              waitedProcessingCids = state.waitedProcessingCids.tail
            )
          )
        )
      case None =>
        context.become(idle(state))
    }
  }

  private def idle(state: State): Receive = {
    case RouteConversation(cid) =>
      persist(ConversationRoutingRequested(cid)) { _ =>
        processNextRoutingRequest(
          state.copy(
            waitedProcessingCids = state.waitedProcessingCids + cid
          )
        )
      }
  }

  private def conversationEventRequested(cid: ConversationId, state: State): Receive = {
    case conversationEvent: ConversationEvent if conversationEvent.cid == cid =>
      val conversation = Conversation(
        cid = conversationEvent.cid,
        initialCommunicationType = conversationEvent.initialCommunicationType,
        selectedGroup = conversationEvent.selectedGroupId,
        employeeId = conversationEvent.employeeId,
        voiceOperators = conversationEvent.voiceOperators
      )
      defineRoutingParameters(conversation, state)
    case command: RouteConversation =>
      persist(ConversationRoutingRequested(command.cid)) { _ =>
        context.become(
          conversationEventRequested(cid, state.copy(
            waitedProcessingCids = state.waitedProcessingCids + command.cid
          ))
        )
      }
    case _ =>
      stash()
  }

  private def defineRoutingParameters(conversation: Conversation, state: State): Unit = {
    if (conversation.voiceOperators.nonEmpty) {
      routeToQueueWithEmployeeSet(conversation, state)
    } else {
      if (conversation.employeeId.isDefined) {
        routeToEmployeeQueue(conversation, state)
      } else {
        routeToGroupQueue(conversation, state)
      }
    }
  }

  private def routeToQueueWithEmployeeSet(conversation: Conversation, state: State): Unit = {
    //TODO Implement routeToQueueWithEmployeeSet function
  }

  private def routeToEmployeeQueue(conversation: Conversation, state: State): Unit = {
    //TODO Implement routeToEmployeeQueue function
  }

  private def routeToGroupQueue(conversation: Conversation, state: State): Unit = {
    val groupId = conversation.selectedGroup
    state.queueIdByGroupId.get(groupId) match {
      case Some(queueId) =>
        state.queues.get(queueId) match {
          case Some(queue) =>
            enqueueConversation(conversation, queue, state)
          case None =>
            throw new IllegalStateException(s"There is no queue in map queues for queueId[$queueId] for groupId[$groupId]")
        }
      case None =>
        val queueId = UUID.randomUUID().toString
        persist(QueueForGroupCreated(queueId, groupId)) { _ =>
          val queue = Queue(queueId, conversations = Set.empty, employees = Set.empty)
          enqueueConversation(conversation, queue, state.copy(
            queueIdByGroupId = state.queueIdByGroupId + (groupId -> queueId),
            queues = state.queues + (queueId -> queue)
          ))
        }
    }
  }

  private def enqueueConversation(conversation: Conversation, queue: Queue, state: State): Unit = {
    val queueId = queue.queueId
    persist(ConversationRouted(queueId, conversation)) { _ =>
      tryToAssignEmployee(conversation, queue, state.copy(
        queues = state.queues + (queueId -> queue.copy(
          conversations = queue.conversations + conversation
        ))
      ))
    }
  }

  private def employeeAssignmentAvailability(busySlots: Option[Long], slotLimit: Option[Long], status: EmployeeStatus): Boolean = {
    slotLimit.flatMap(slotLimit =>
      busySlots.map(busySlots =>
        busySlots < slotLimit
      )
    ).getOrElse(true) && status == EmployeeStatus.Online
  }

  private def filterEmployeesAccordingToAssignmentAvailability(conversation: Conversation, employees: Seq[Employee]): Seq[Employee] = {
    conversation.initialCommunicationType match {
      case CommunicationType.ChatSite =>
        employees.filter(employee => employeeAssignmentAvailability(employee.busySlots.chatSite, employee.limits.chatSiteLimit, employee.status))
      case _ =>
        employees.filter(employee => employeeAssignmentAvailability(employee.busySlots.otherChannels, employee.limits.otherChannels, employee.status))
    }
  }

  private def selectEmployeeAccordingToAssignmentSettings(employees: Seq[Employee], assignmentSettings: AssignmentSettings): Option[Employee] = {
    assignmentSettings match {
      case AssignmentSettings.Fair =>
        //TODO Необходимо не только отсортировать по размеру, но в случае совпадения выбрать случайного из группы
        employees.sortBy(employee => employee.conversations.size)(Ordering[Int].reverse).headOption
      case AssignmentSettings.Random =>
        //TODO Необходимо выбрать случйного из списка
        employees.headOption
    }
  }

  private def tryToAssignEmployee(conversation: Conversation, queue: Queue, state: State): Unit = {
    val employees = queue.employees.map(employeeId => state.employees(employeeId)).toSeq
    val filteredEmployees = filterEmployeesAccordingToAssignmentAvailability(conversation, employees)
    selectEmployeeAccordingToAssignmentSettings(filteredEmployees, state.assignmentSettings) match {
      case Some(employee) =>
        assignEmployeeToConversation(conversation, queue, employee, state)
      case None =>
        processNextRoutingRequest(state)
    }
  }

  private def assignEmployeeToConversation(conversation: Conversation, queue: Queue, employee: Employee, state: State): Unit = {
    persist(ConversationAssigned(queue.queueId, conversation.cid, employee.employeeId)) { _ =>
      processNextRoutingRequest(state.copy(
        queues = state.queues + (queue.queueId -> queue.copy(
          conversations = queue.conversations - conversation
        )),
        employees = state.employees + (employee.employeeId -> employee.copy(
          conversations = employee.conversations + conversation
        ))
      ))
    }
  }

}

object RoutingModuleActor {

  type QueueId = String
  type GroupId = String
  type EmployeeId = String
  type ConversationId = String

  case class Conversation(cid: ConversationId,
                          initialCommunicationType: CommunicationType,
                          selectedGroup: GroupId,
                          employeeId: Option[EmployeeId],
                          voiceOperators: Set[EmployeeId])

  case class PersistedQueue(queueId: QueueId,
                            conversations: Set[Conversation],
                            employees: Set[EmployeeId])

  case class PersistedEmployee(employeeId: EmployeeId,
                               conversations: Set[Conversation])

  case class PersistedState(waitedProcessingCids: Set[ConversationId] = Set.empty[ConversationId],
                            queueIdByGroupId: Map[GroupId, QueueId] = Map.empty[GroupId, QueueId],
                            queueIdByEmployeeId: Map[EmployeeId, QueueId] = Map.empty[EmployeeId, QueueId],
                            queueIdByConversationId: Map[ConversationId, QueueId] = Map.empty[ConversationId, QueueId],
                            queues: Map[QueueId, PersistedQueue] = Map.empty[QueueId, PersistedQueue],
                            employees: Map[EmployeeId, PersistedEmployee] = Map.empty[EmployeeId, PersistedEmployee])

  case class DomainEvent(event: Event, state: PersistedState)

  object PersistedState {
    def applyEvent: PartialFunction[DomainEvent, PersistedState] = {
      case DomainEvent(ConversationRoutingRequested(cid), state) =>
        state.copy(
          waitedProcessingCids = state.waitedProcessingCids + cid
        )
      case DomainEvent(QueueForGroupCreated(queueId, groupId), state) =>
        state.copy(
          queueIdByGroupId = state.queueIdByGroupId + (groupId -> queueId),
          queues = state.queues + (queueId -> PersistedQueue(
            queueId = queueId,
            conversations = Set.empty,
            employees = Set.empty
          ))
        )
      case DomainEvent(QueueForEmployeeCreated(queueId, employeeId), state) =>
        state.copy(
          queueIdByEmployeeId = state.queueIdByEmployeeId + (employeeId -> queueId),
          queues = state.queues + (queueId -> PersistedQueue(
            queueId = queueId,
            conversations = Set.empty,
            employees = Set(employeeId)
          ))
        )
      case DomainEvent(QueueForEmployeeSetCreated(queueId, cid, employeeSet), state) =>
        state.copy(
          queueIdByConversationId = state.queueIdByConversationId + (cid -> queueId),
          queues = state.queues + (queueId -> PersistedQueue(
            queueId = queueId,
            conversations = Set.empty,
            employees = employeeSet
          ))
        )
      case DomainEvent(event@ConversationRouted(queueId, conversation), state) =>
        state.queues.get(queueId) match {
          case Some(queue) if queue.queueId == queueId =>
            state.copy(
              waitedProcessingCids = state.waitedProcessingCids - conversation.cid,
              queues = state.queues + (queueId -> queue.copy(
                conversations = queue.conversations + conversation
              ))
            )
          case Some(queue) =>
            throw new IllegalStateException(s"The Queue[$queueId] must correspond to queueId[$queueId] of domain event [$event]; state: [$state]")
          case None =>
            throw new IllegalStateException(s"The Queue[$queueId] must be when handled domain event [$event]; state: [$state]")
        }
      case DomainEvent(ConversationAssigned(queueId, conversationId, employeeId), state) =>
        //TODO Написать обработку назначения Опреатора на Обращение
        state
    }
  }

  case class InitializationState(persistedState: PersistedState,
                                 assignmentSettings: Option[AssignmentSettings] = None,
                                 employeeLimitsMap: Map[EmployeeId, EmployeeLimits] = Map.empty)

  case class BusySlots(chatSite: Option[Long], otherChannels: Option[Long])

  case class Employee(employeeId: EmployeeId,
                      status: EmployeeStatus,
                      limits: EmployeeLimits,
                      busySlots: BusySlots,
                      conversations: Set[Conversation])

  case class Queue(queueId: QueueId,
                   conversations: Set[Conversation],
                   employees: Set[EmployeeId])

  case class State(waitedProcessingCids: Set[ConversationId],
                   queueIdByGroupId: Map[GroupId, QueueId],
                   queueIdByEmployeeId: Map[EmployeeId, QueueId],
                   queueIdByConversationId: Map[ConversationId, QueueId],
                   queues: Map[QueueId, Queue],
                   employees: Map[EmployeeId, Employee],
                   assignmentSettings: AssignmentSettings)

  trait Event
  case class ConversationRoutingRequested(cid: ConversationId) extends Event
  case class QueueForGroupCreated(queueId: QueueId, groupId: GroupId) extends Event
  case class QueueForEmployeeCreated(queueId: QueueId, employeeId: EmployeeId) extends Event
  case class QueueForEmployeeSetCreated(queueId: QueueId, cid: ConversationId, employeeSet: Set[EmployeeId]) extends Event
  case class ConversationRouted(queueId: QueueId, conversation: Conversation) extends Event
  case class ConversationAssigned(queueId: QueueId, cid: ConversationId, employeeId: EmployeeId) extends Event

  trait Command
  case class GetQueueByGroupId(groupId: GroupId) extends Command

}

