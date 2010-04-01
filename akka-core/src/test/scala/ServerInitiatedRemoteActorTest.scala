package se.scalablesolutions.akka.actor

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.junit.JUnitSuite
import org.junit.{Test, Before, After}

import se.scalablesolutions.akka.remote.{RemoteServer, RemoteClient}
import se.scalablesolutions.akka.dispatch.Dispatchers

object ServerInitiatedRemoteActorTest {
  val HOSTNAME = "localhost"
  val PORT = 9990
  var server: RemoteServer = null

  object Global {
    val oneWay = new CountDownLatch(1)
    var remoteReply = new CountDownLatch(1)
  }

  class RemoteActorSpecActorUnidirectional extends Actor {
    dispatcher = Dispatchers.newThreadBasedDispatcher(this)
    start

    def receive = {
      case "OneWay" =>
        println("================== ONEWAY")
        Global.oneWay.countDown
    }
  }

  class RemoteActorSpecActorBidirectional extends Actor {
    start
    def receive = {
      case "Hello" =>
        reply("World")
      case "Failure" =>
        throw new RuntimeException("expected")
    }
  }

  case class Send(actor: Actor)

  class RemoteActorSpecActorAsyncSender extends Actor {
    start
    def receive = {
      case Send(actor: Actor) =>
        actor ! "Hello"
      case "World" =>
        Global.remoteReply.countDown
    }

    def send(actor: Actor) {
      this ! Send(actor)
    }
  }
}

class ServerInitiatedRemoteActorTest extends JUnitSuite {
  import ServerInitiatedRemoteActorTest._

  import Actor.Sender.Self
  se.scalablesolutions.akka.config.Config.config

  private val unit = TimeUnit.MILLISECONDS

  @Before
  def init() {
    server = new RemoteServer()

    server.start(HOSTNAME, PORT)

    server.register(new RemoteActorSpecActorUnidirectional)
    server.register(new RemoteActorSpecActorBidirectional)
    server.register(new RemoteActorSpecActorAsyncSender)

    Thread.sleep(1000)
  }

  // make sure the servers shutdown cleanly after the test has finished
  @After
  def finished() {
    server.shutdown
    RemoteClient.shutdownAll
    Thread.sleep(1000)
  }

  @Test
  def shouldSendOneWay = {
    val actor = RemoteClient.actorFor(
      "se.scalablesolutions.akka.actor.ServerInitiatedRemoteActorTest$RemoteActorSpecActorUnidirectional",
      5000L,
      HOSTNAME, PORT)
    val result = actor ! "OneWay"
    Global.oneWay.await(1, TimeUnit.SECONDS)
    assert(0 === Global.oneWay.getCount)
    actor.stop
  }

  @Test
  def shouldSendReplyAsync = {
    val actor = RemoteClient.actorFor(
      "se.scalablesolutions.akka.actor.ServerInitiatedRemoteActorTest$RemoteActorSpecActorBidirectional",
      5000L,
      HOSTNAME, PORT)
    val result = actor !! "Hello"
    assert("World" === result.get.asInstanceOf[String])
    actor.stop
  }

  @Test
  def shouldSendRemoteReply = {
    implicit val timeout = 500000000L
    val actor = RemoteClient.actorFor(
      "se.scalablesolutions.akka.actor.ServerInitiatedRemoteActorTest$RemoteActorSpecActorBidirectional",
      timeout,
      HOSTNAME, PORT)

    val sender = new RemoteActorSpecActorAsyncSender
    sender.setReplyToAddress(HOSTNAME, PORT)
    sender.start
    sender.send(actor)
    Global.remoteReply.await(1, TimeUnit.SECONDS)
    assert(0 === Global.remoteReply.getCount)
    actor.stop
  }

  @Test
  def shouldSendReceiveException = {
    implicit val timeout = 500000000L
    val actor = RemoteClient.actorFor(
      "se.scalablesolutions.akka.actor.ServerInitiatedRemoteActorTest$RemoteActorSpecActorBidirectional",
      timeout,
      HOSTNAME, PORT)
    try {
      actor !! "Failure"
      fail("Should have thrown an exception")
    } catch {
      case e =>
        assert("expected" === e.getMessage())
    }
    actor.stop
  }
}
                          