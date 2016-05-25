package ekiaa.akka.otp.example

import akka.actor._
import com.typesafe.scalalogging.StrictLogging

object FirstLevelSupervisor {

  var instance: Option[ActorRef] = None

  def start(context: ActorContext) = {
    synchronized {
      instance match {
        case None =>
          instance = Some(context.actorOf(props, "firstLevelSupervisor"))
        case _ =>
      }
    }
  }

  def start(system: ActorSystem) = {
    synchronized {
      instance match {
        case None =>
          instance = Some(system.actorOf(props, "firstLevelSupervisor"))
        case _ =>
      }
    }
  }

  def props: Props = Props(new FirstLevelSupervisor)

}

class FirstLevelSupervisor extends Actor with StrictLogging {

  override def preStart() = {
    logger.debug(s"FirstLevelWorker started with path ${context.self.path}")
    FirstLevelWorker.start("firstLevelWorker", context)
  }

  val receive: Receive = {

    case _ =>

  }

}
