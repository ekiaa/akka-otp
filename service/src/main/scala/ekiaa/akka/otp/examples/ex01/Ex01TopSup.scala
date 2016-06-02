package ekiaa.akka.otp.examples.ex01

import akka.actor._
import com.typesafe.scalalogging.StrictLogging

object Ex01TopSup {

  var instance: Option[ActorRef] = None

  def start(system: ActorSystem) = {
    synchronized {
      instance match {
        case None =>
          instance = Some(system.actorOf(props, "Ex01TopSup"))
        case _ =>
      }
    }
  }

  def props: Props = Props(new Ex01TopSup)

}

class Ex01TopSup extends Actor with StrictLogging {

  override def preStart() = {
    logger.debug(s"Ex01TopSup started with path ${context.self.path}")
    Ex01Manager.start(context)
    Ex01WorkerSup.start(context)
  }

  val receive: Receive = {

    case _ =>

  }

}
