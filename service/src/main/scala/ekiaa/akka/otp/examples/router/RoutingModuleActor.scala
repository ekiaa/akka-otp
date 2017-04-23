package ekiaa.akka.otp.examples.router

import java.util.UUID

import RoutingSystem._
import akka.pattern.{ask, pipe}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import com.typesafe.scalalogging.StrictLogging

class RoutingModuleActor(accountId: String,
                         conversationRepository: ConversationRepository,
                         assignmentSettingsRepository: AssignmentSettingsRepository
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
    pipe(assignmentSettingsRepository.getAssignmentSettings(accountId)) to self
    context.become(
      assignmentSettingsRequested(
        InitializationState(persistedState = persistedState)
      )
    )
  }

  private def assignmentSettingsRequested(initializationState: InitializationState): Receive = {
    case assignmentSettings: AssignmentSettings =>
      processNextRoutingRequest(
        OperatingState(
          initializationState.copy(
            assignmentSettings = Some(assignmentSettings)
          )
        )
      )
    case _ =>
      stash()
  }

  def processNextRoutingRequest(state: State): Unit = {
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


  def idle(state: State): Receive = {
    case RouteConversation(cid) =>
      persist(ConversationRoutingRequested(cid)) { _ =>
        processNextRoutingRequest(
          state.copy(
            waitedProcessingCids = state.waitedProcessingCids + cid
          )
        )
      }
  }

  def conversationEventRequested(cid: ConversationId, state: State): Receive = {
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
      persist(ConversationRoutingRequested(cid)) { _ =>
        context.become(
          conversationEventRequested(cid,
            state.copy(waitedProcessingCids = state.waitedProcessingCids + cid)
          )
        )
      }
    case _ =>
      stash()
  }

  def defineRoutingParameters(conversation: Conversation, state: State): Unit = {
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

  def routeToQueueWithEmployeeSet(conversation: Conversation, state: State): Unit = {
    //TODO Implement routeToQueueWithEmployeeSet function
  }

  def routeToEmployeeQueue(conversation: Conversation, state: State): Unit = {
    //TODO Implement routeToEmployeeQueue function
  }

  def routeToGroupQueue(conversation: Conversation, state: State): Unit = {
    state.queueIdByGroupId.get(conversation.selectedGroup) match {
      case Some(queueId) =>
        state.queues.get(queueId) match {
          case Some(queue) =>
            enqueueConversation(conversation, queue, state)
          case None =>

        }
      case None =>

    }
  }

  def enqueueConversation(conversation: Conversation, queue: Queue, state: State): Unit = {
    tryToAssignEmployee(conversation.cid, queue.queueId, state.copy(
      queues = state.queues + (queue.queueId -> queue.copy(
        conversations = queue.conversations + conversation
      ))
    ))
  }

  def tryToAssignEmployee(cid: ConversationId, queueId: QueueId, state: State): Unit = {
    //TODO
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
    }
  }

  case class InitializationState(persistedState: PersistedState,
                                 assignmentSettings: Option[AssignmentSettings] = None)

  case class Queue(queueId: QueueId,
                   conversations: Set[Conversation],
                   employees: Set[EmployeeId])

  case class Employee(employeeId: EmployeeId,
                      conversations: Set[Conversation])

  case class State(waitedProcessingCids: Set[ConversationId],
                   queueIdByGroupId: Map[GroupId, QueueId],
                   queueIdByEmployeeId: Map[EmployeeId, QueueId],
                   queueIdByConversationId: Map[ConversationId, QueueId],
                   queues: Map[QueueId, Queue],
                   employees: Map[EmployeeId, Employee],
                   assignmentSettings: AssignmentSettings)

  object OperatingState {
    def apply(initializationState: InitializationState): State = {
      require(initializationState.assignmentSettings.isDefined,
        "InitializationState.assignmentSettings should be defined when OperatingState preparing")
      State(
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
          employeeId -> Employee(
            employeeId = persistedEmployee.employeeId,
            conversations = persistedEmployee.conversations
          )
        },
        assignmentSettings = initializationState.assignmentSettings.get
      )
    }
  }

  trait Event
  case class ConversationRoutingRequested(cid: ConversationId) extends Event
  case class QueueForGroupCreated(queueId: QueueId, groupId: GroupId) extends Event
  case class QueueForEmployeeCreated(queueId: QueueId, employeeId: EmployeeId) extends Event
  case class QueueForEmployeeSetCreated(queueId: QueueId, cid: ConversationId, employeeSet: Set[EmployeeId]) extends Event
  case class ConversationRouted(queueId: QueueId, conversation: Conversation) extends Event

  trait Command
  case class GetQueueByGroupId(groupId: GroupId) extends Command

}

