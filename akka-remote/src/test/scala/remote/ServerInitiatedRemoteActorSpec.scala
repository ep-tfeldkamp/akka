package akka.actor.remote

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import akka.util._

import akka.actor.Actor._
import akka.actor.{ActorRegistry, ActorRef, Actor}
import akka.remote. {NettyRemoteSupport, RemoteServer, RemoteClient}

object ServerInitiatedRemoteActorSpec {
  case class Send(actor: ActorRef)

  class ReplyHandlerActor(latch: CountDownLatch, expect: String) extends Actor {
    def receive = {
      case x: String if x == expect => latch.countDown
    }
  }

  def replyHandler(latch: CountDownLatch, expect: String) = Some(actorOf(new ReplyHandlerActor(latch, expect)).start)

  class RemoteActorSpecActorUnidirectional extends Actor {
    def receive = {
      case "Ping" => self.reply_?("Pong")
    }
  }

  class RemoteActorSpecActorBidirectional extends Actor {

    def receive = {
      case "Hello" =>
        self.reply("World")
      case "Failure" =>
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }

  object RemoteActorSpecActorAsyncSender {
    val latch = new CountDownLatch(1)
  }
  class RemoteActorSpecActorAsyncSender extends Actor {

    def receive = {
      case Send(actor: ActorRef) =>
        actor ! "Hello"
      case "World" =>
        RemoteActorSpecActorAsyncSender.latch.countDown
    }
  }
}

@RunWith(classOf[JUnitRunner])
class ServerInitiatedRemoteActorSpec extends
  WordSpec with
  MustMatchers with
  BeforeAndAfterAll with
  BeforeAndAfterEach {
  import ServerInitiatedRemoteActorSpec._
  import ActorRegistry.remote
  private val unit = TimeUnit.MILLISECONDS
  val (host, port) = (remote.hostname,remote.port)

  var optimizeLocal_? = remote.asInstanceOf[NettyRemoteSupport].optimizeLocalScoped_?

  override def beforeAll() {
    remote.asInstanceOf[NettyRemoteSupport].optimizeLocal.set(false) //Can't run the test if we're eliminating all remote calls
    remote.start()
  }

  override def afterAll() {
    remote.asInstanceOf[NettyRemoteSupport].optimizeLocal.set(optimizeLocal_?) //Reset optimizelocal after all tests
  }

  override def afterEach() {
    ActorRegistry.shutdownAll
    super.afterEach
  }

  "Server-managed remote actors" should {
    "sendWithBang" in {
      val latch = new CountDownLatch(1)
      implicit val sender = replyHandler(latch, "Pong")
      remote.register(actorOf[RemoteActorSpecActorUnidirectional])
      val actor = remote.actorFor("akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorUnidirectional",5000L,host, port)

      actor ! "Ping"
      latch.await(1, TimeUnit.SECONDS) must be (true)
    }

    "sendWithBangBangAndGetReply" in {
      remote.register(actorOf[RemoteActorSpecActorBidirectional])
      val actor = remote.actorFor("akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorBidirectional", 5000L,host, port)
      (actor !! "Hello").as[String].get must equal ("World")
    }

    "sendWithBangAndGetReplyThroughSenderRef" in {
      remote.register(actorOf[RemoteActorSpecActorBidirectional])
      implicit val timeout = 500000000L
      val actor = remote.actorFor(
        "akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorBidirectional", timeout,host, port)
      val sender = actorOf[RemoteActorSpecActorAsyncSender].start
      sender ! Send(actor)
      RemoteActorSpecActorAsyncSender.latch.await(1, TimeUnit.SECONDS) must be (true)
    }

    "sendWithBangBangAndReplyWithException" in {
      remote.register(actorOf[RemoteActorSpecActorBidirectional])
      implicit val timeout = 500000000L
      val actor = remote.actorFor(
          "akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorBidirectional", timeout, host, port)
      try {
        actor !! "Failure"
        fail("Should have thrown an exception")
      } catch {
        case e => e.getMessage must equal ("Expected exception; to test fault-tolerance")
      }
    }

    "notRecreateRegisteredActor" in {
      val latch = new CountDownLatch(1)
      implicit val sender = replyHandler(latch, "Pong")
      remote.register(actorOf[RemoteActorSpecActorUnidirectional])
      val actor = remote.actorFor("akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorUnidirectional", host, port)
      val numberOfActorsInRegistry = ActorRegistry.actors.length
      actor ! "Ping"
      latch.await(1, TimeUnit.SECONDS) must be (true)
      numberOfActorsInRegistry must equal (ActorRegistry.actors.length)
    }

    "UseServiceNameAsIdForRemoteActorRef" in {
      val latch = new CountDownLatch(3)
      implicit val sender = replyHandler(latch, "Pong")
      remote.register(actorOf[RemoteActorSpecActorUnidirectional])
      remote.register("my-service", actorOf[RemoteActorSpecActorUnidirectional])
      val actor1 = remote.actorFor("akka.actor.remote.ServerInitiatedRemoteActorSpec$RemoteActorSpecActorUnidirectional", host, port)
      val actor2 = remote.actorFor("my-service", host, port)
      val actor3 = remote.actorFor("my-service", host, port)

      actor1 ! "Ping"
      actor2 ! "Ping"
      actor3 ! "Ping"

      latch.await(1, TimeUnit.SECONDS) must be (true)
      actor1.uuid must not equal actor2.uuid
      actor1.uuid must not equal actor3.uuid
      actor1.id must not equal actor2.id
      actor2.id must equal (actor3.id)
    }

    "shouldFindActorByUuid" in {
      val latch = new CountDownLatch(2)
      implicit val sender = replyHandler(latch, "Pong")
      val actor1 = actorOf[RemoteActorSpecActorUnidirectional]
      val actor2 = actorOf[RemoteActorSpecActorUnidirectional]
      remote.register("uuid:" + actor1.uuid, actor1)
      remote.register("my-service", actor2)

      val ref1 = remote.actorFor("uuid:" + actor1.uuid, host, port)
      val ref2 = remote.actorFor("my-service", host, port)

      ref1 ! "Ping"
      ref2 ! "Ping"
      latch.await(1, TimeUnit.SECONDS) must be (true)
    }

    "shouldRegisterAndUnregister" in {
      val actor1 = actorOf[RemoteActorSpecActorUnidirectional]

      remote.register("my-service-1", actor1)
      remote.actors.get("my-service-1") must not be null

      remote.unregister("my-service-1")
      remote.actors.get("my-service-1") must be (null)
    }

    "shouldRegisterAndUnregisterByUuid" in {
      val actor1 = actorOf[RemoteActorSpecActorUnidirectional]
      val uuid = "uuid:" + actor1.uuid

      remote.register(uuid, actor1)
      remote.actorsByUuid.get(actor1.uuid.toString) must not be null

      remote.unregister(uuid)
      remote.actorsByUuid.get(actor1.uuid) must be (null)
    }
  }
}

