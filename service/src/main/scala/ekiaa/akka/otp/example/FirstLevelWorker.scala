package ekiaa.akka.otp.example

import akka.actor.{Props, ActorRef, ActorContext, Actor}
import com.typesafe.scalalogging.StrictLogging
import ekiaa.akka.otp.example.FirstLevelWorker.DoSmth

import scala.collection.concurrent.TrieMap

object FirstLevelWorker {

  val map: TrieMap[String, ActorRef] = TrieMap.empty[String, ActorRef]

  def start(name: String, context: ActorContext) = {
    map.getOrElseUpdate(name, {
      context.actorOf(props, name)
    })
  }

  def props: Props = Props(new FirstLevelWorker)

  final case class DoSmth(msg: String)

  def doSmth(name: String, msg: String) = {
    map.get(name) match {
      case Some(actorRef) =>
        actorRef ! DoSmth(msg)
      case None =>
    }
  }

}

class FirstLevelWorker extends Actor with StrictLogging {

  override def preStart() = {
    logger.debug(s"FirstLevelWorker started with path ${context.self.path}")
  }

  val receive: Receive = {
    case message: DoSmth =>
      logger.debug(s"FirstLevelWorker receive $message")
    case _ =>
  }

}
