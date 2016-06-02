package ekiaa.akka.otp.examples.ex01

import akka.actor.{Actor, ActorContext, ActorRef, Props}
import com.typesafe.scalalogging.StrictLogging

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

object Ex01Worker {

  def start(name: String): Future[Any] = {
    Ex01WorkerSup.startChild(name)
  }

  val map: TrieMap[String, ActorRef] = TrieMap.empty[String, ActorRef]

  def start(args: Any, context: ActorContext): Future[Any] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    Future {
      args match {
        case name: String =>
          Some(map.getOrElseUpdate(name, {
            context.actorOf(props, name)
          }))
        case _ =>
          throw new Exception
      }
    }
  }

  def props: Props = Props(new Ex01Worker)

  final case class DoSmth(msg: String)

  def doSmth(name: String, msg: String) = {
    map.get(name) match {
      case Some(worker) =>
        worker ! DoSmth(msg)
      case None =>
        import scala.concurrent.ExecutionContext.Implicits.global
        Ex01Manager.startWorker(name).map(
          {
            case Some(worker: ActorRef) =>
              worker ! DoSmth(msg)
            case result =>
              throw new Exception(s"Ex01Manager.startWorker(name) return: $result")
          }
        )
    }
  }

}

class Ex01Worker extends Actor with StrictLogging {

  override def preStart() = {
    logger.debug(s"Ex01Worker started with path ${context.self.path}")
  }

  val receive: Receive = {
    case message: Ex01Worker.DoSmth =>
      logger.debug(s"Ex01Worker receive $message")
    case _ =>
  }

}
