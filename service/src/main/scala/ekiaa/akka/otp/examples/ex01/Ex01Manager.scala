package ekiaa.akka.otp.examples.ex01

import akka.actor.{Actor, Props, ActorContext, ActorRef}
import akka.pattern.ask
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.Future
import scala.concurrent.duration._

object Ex01Manager {

  var instance: Option[ActorRef] = None

  def start(context: ActorContext) = {
    synchronized {
      instance match {
        case None =>
          instance = Some(context.actorOf(props, "Ex01Manager"))
        case _ =>
      }
    }
  }

  def props: Props = Props(new Ex01Manager)

  private final case class StartWorker(name: String)

  def startWorker(name: String): Future[Any] = {
    instance match {
      case Some(manager) =>
        ask(manager, StartWorker(name))(30.seconds)
      case None =>
        import scala.concurrent.ExecutionContext.Implicits.global
        Future { None }
    }
  }

}

class Ex01Manager extends Actor with StrictLogging {

  override def preStart() = {
    logger.debug(s"Ex01Manager started with path ${context.self.path}")
  }

  import Ex01Manager._

  val receive: Receive = {
    case StartWorker(name) =>
      val replyTo = sender()
      Ex01Worker.map.get(name) match {
        case result @ Some(_) =>
          replyTo ! result
        case None =>
          import scala.concurrent.ExecutionContext.Implicits.global
          Ex01Worker.start(name).map(result => { replyTo ! result })
      }
    case _ =>
  }

}
