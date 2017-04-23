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

  def conversationEventRequested(cid: String, state: State): Receive = {
    case conversation: Conversation if conversation.cid == cid =>
      val conv =
        Conversation(
          cid = cid,
          groupId = conversation.selectedGroupId,
          commType = conversation.initialCommunicationType
        )
      pipe (self ask GetQueueByGroupId(conv.groupId)) to self
      context.become(queueRequested(conv, state))
    case command: RouteConversation =>
      persist(ConversationRoutingRequested(command.cid)) { _ => }
  }

  def queueRequested(conv: Conversation, state: State): Receive = {
    case GetQueueByGroupId(groupId) if conv.groupId == groupId =>
      state.queueByGroupId.get(groupId) match {
        case Some(queueId) =>
          val queue = queueRepository.get(queueId).getOrElse(queueFactory.create(queueId))
          sender() ! queue
        case None =>
          val queueId = UUID.randomUUID().toString
          persist(QueueForGroupCreated(groupId, queueId)) { _ =>
            val queue = queueFactory.create(queueId)
            queueRepository.save(queue)
            sender() ! queue
          }
      }
    case queue: Queue =>

    case command: RouteConversation =>
      persist(ConversationRoutingRequested(command.cid)) { _ => }
  }

  def obtainQueueForGroupId(conv: Conversation, state: State): Unit = {
    state.queueByGroupId.get(conv.groupId) match {
      case Some(queueId) =>
        val queue = queueRepository.get(queueId).getOrElse(createQueue(queueId))
        enqueueConversation(queue, conv, state)
      case None =>
        val queueId = UUID.randomUUID().toString
        persist(QueueForGroupCreated(conv.groupId, queueId)) { _ =>
          val queue = createQueue(queueId)
          enqueueConversation(queue, conv,
            state.copy(queueByGroupId = state.queueByGroupId + (conv.groupId -> queueId))
          )
        }
    }
  }

  def enqueueConversation(queue: Queue, conv: Conversation, state: State): Unit = {
    persist(ConversationRouted(conv, queue.id)) { _ =>
      queue.addConv(conv)
      queueRepository.save(queue)
      tryAssignEmployee(queue, conv, state)
    }
  }

  def tryAssignEmployee(queue: Queue, conv: Conversation, state: State): Unit = {

  }

  def createQueue(queueId: String): Queue = {
    val queue = queueFactory.create(queueId)
    queueRepository.save(queue)
    queue
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

