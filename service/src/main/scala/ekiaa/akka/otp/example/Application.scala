package ekiaa.akka.otp.example

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging

object Application extends App with StrictLogging {

  logger.debug("Starting")

  val system = ActorSystem("OTP-Example")

  FirstLevelSupervisor.start(system)

  Thread.sleep(1000)

  FirstLevelWorker.doSmth("firstLevelWorker", "msg")

  system.terminate()

}
