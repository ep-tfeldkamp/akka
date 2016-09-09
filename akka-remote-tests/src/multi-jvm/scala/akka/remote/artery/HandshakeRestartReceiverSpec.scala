/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor._
import akka.remote.AddressUidExtension
import akka.remote.RARP
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory

object HandshakeRestartReceiverSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString(s"""
       akka {
         loglevel = INFO
         actor.provider = remote
         remote.artery {
           enabled = on
         }
       }
       """)))

  class Subject extends Actor {
    def receive = {
      case "shutdown" ⇒ context.system.terminate()
      case "identify" ⇒ sender() ! (AddressUidExtension(context.system).addressUid → self)
    }
  }

}

class HandshakeRestartReceiverSpecMultiJvmNode1 extends HandshakeRestartReceiverSpec
class HandshakeRestartReceiverSpecMultiJvmNode2 extends HandshakeRestartReceiverSpec

abstract class HandshakeRestartReceiverSpec
  extends MultiNodeSpec(HandshakeRestartReceiverSpec)
  with STMultiNodeSpec with ImplicitSender {

  import HandshakeRestartReceiverSpec._

  override def initialParticipants = roles.size

  override def afterAll(): Unit = {
    super.afterAll()
  }

  def identifyWithUid(rootPath: ActorPath, actorName: String, timeout: FiniteDuration = remainingOrDefault): (Int, ActorRef) = {
    within(timeout) {
      system.actorSelection(rootPath / "user" / actorName) ! "identify"
      expectMsgType[(Int, ActorRef)]
    }
  }

  "Artery Handshake" must {

    "detect restarted receiver and initiate new handshake" in {
      runOn(second) {
        system.actorOf(Props[Subject], "subject")
      }
      enterBarrier("subject-started")

      runOn(first) {
        val secondRootPath = node(second)
        val (secondUid, _) = identifyWithUid(secondRootPath, "subject", 5.seconds)

        val secondAddress = node(second).address
        val secondAssociation = RARP(system).provider.transport.asInstanceOf[ArteryTransport].association(secondAddress)
        val secondUniqueRemoteAddress = Await.result(secondAssociation.associationState.uniqueRemoteAddress, 3.seconds)
        secondUniqueRemoteAddress.address should ===(secondAddress)
        secondUniqueRemoteAddress.uid should ===(secondUid)

        enterBarrier("before-shutdown")
        testConductor.shutdown(second).await

        within(30.seconds) {
          awaitAssert {
            identifyWithUid(secondRootPath, "subject2", 1.second)
          }
        }
        val (secondUid2, subject2) = identifyWithUid(secondRootPath, "subject2")
        secondUid2 should !==(secondUid)
        val secondUniqueRemoteAddress2 = Await.result(secondAssociation.associationState.uniqueRemoteAddress, 3.seconds)
        secondUniqueRemoteAddress2.uid should ===(secondUid2)
        secondUniqueRemoteAddress2.address should ===(secondAddress)
        secondUniqueRemoteAddress2 should !==(secondUniqueRemoteAddress)

        subject2 ! "shutdown"
      }

      runOn(second) {
        val addr = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        enterBarrier("before-shutdown")

        Await.result(system.whenTerminated, 10.seconds)

        val freshSystem = ActorSystem(system.name, ConfigFactory.parseString(s"""
              akka.remote.artery.canonical.port = ${addr.port.get}
              """).withFallback(system.settings.config))
        freshSystem.actorOf(Props[Subject], "subject2")

        Await.result(freshSystem.whenTerminated, 45.seconds)
      }
    }

  }
}
