package ekiaa.akka.otp.examples

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import ekiaa.akka.otp.examples.ex01.{Ex01Worker, Ex01TopSup}

object Application extends App with StrictLogging {

  logger.debug("Starting")

  val system = ActorSystem("OTP-Example")

  Ex01TopSup.start(system)

  Thread.sleep(1000)

  Ex01Worker.doSmth("ex01Worker", "Message to Ex01Worker")

  Thread.sleep(1000)

  system.terminate()

}
