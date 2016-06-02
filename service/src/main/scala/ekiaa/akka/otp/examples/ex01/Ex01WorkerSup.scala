package ekiaa.akka.otp.examples.ex01

import akka.actor.{Actor, ActorContext, Props, ActorRef}
import akka.pattern.ask
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.duration._

import scala.concurrent.Future

object Ex01WorkerSup {

  var instance: Option[ActorRef] = None

  def start(context: ActorContext) = {
    synchronized {
      instance match {
        case None =>
          instance = Some(context.actorOf(props, "Ex01WorkerSup"))
        case _ =>
      }
    }
  }

  def props: Props = Props(new Ex01WorkerSup)

  private final case class StartChild(args: Any)

  def startChild(args: Any): Future[Any] = {
    instance match {
      case Some(supervisor) =>
        ask(supervisor, StartChild(args))(30.seconds)
      case _ =>
        import scala.concurrent.ExecutionContext.Implicits.global
        Future { None }
    }
  }
}

class Ex01WorkerSup extends Actor with StrictLogging {

  override def preStart() = {
    logger.debug(s"Ex01WorkerSup started with path ${context.self.path}")
  }

  import Ex01WorkerSup._

  val receive: Receive = {

    case StartChild(args) =>
      import scala.concurrent.ExecutionContext.Implicits.global
      val replyTo = sender()
      Ex01Worker.start(args, context).map(result => { replyTo ! result })
    case _ =>

  }

}
