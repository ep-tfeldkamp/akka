/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.Identify
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.Terminated
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._

object RemoteNodeDeathWatchMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("""
      akka.loglevel = INFO
      akka.remote.log-remote-lifecycle-events = off
      """)))

  case class WatchIt(watchee: ActorRef)
  case class UnwatchIt(watchee: ActorRef)
  case object Ack

  /**
   * Forwarding `Terminated` to non-watching testActor is not possible,
   * and therefore the `Terminated` message is wrapped.
   */
  case class WrappedTerminated(t: Terminated)

  class ProbeActor(testActor: ActorRef) extends Actor {
    def receive = {
      case WatchIt(watchee) ⇒
        context watch watchee
        sender ! Ack
      case UnwatchIt(watchee) ⇒
        context unwatch watchee
        sender ! Ack
      case t: Terminated ⇒
        testActor forward WrappedTerminated(t)
      case msg ⇒ testActor forward msg
    }
  }

}

// Several different variations of the test

class RemoteNodeDeathWatchFastMultiJvmNode1 extends RemoteNodeDeathWatchFastSpec
class RemoteNodeDeathWatchFastMultiJvmNode2 extends RemoteNodeDeathWatchFastSpec
abstract class RemoteNodeDeathWatchFastSpec extends RemoteNodeDeathWatchSpec {
  override def scenario = "fast"
}

class RemoteNodeDeathWatchSlowMultiJvmNode1 extends RemoteNodeDeathWatchSlowSpec
class RemoteNodeDeathWatchSlowMultiJvmNode2 extends RemoteNodeDeathWatchSlowSpec
abstract class RemoteNodeDeathWatchSlowSpec extends RemoteNodeDeathWatchSpec {
  override def scenario = "slow"
  override def sleep(): Unit = Thread.sleep(3000)
}

abstract class RemoteNodeDeathWatchSpec
  extends MultiNodeSpec(RemoteNodeDeathWatchMultiJvmSpec)
  with STMultiNodeSpec with ImplicitSender {

  import RemoteNodeDeathWatchMultiJvmSpec._
  import RemoteWatcher._

  def scenario: String
  // Possible to override to let them heartbeat for a while.
  def sleep(): Unit = ()

  override def initialParticipants = roles.size

  lazy val remoteWatcher: ActorRef = {
    system.actorSelection("/system/remote-watcher") ! Identify(None)
    expectMsgType[ActorIdentity].ref.get
  }

  def identify(role: RoleName, actorName: String): ActorRef = {
    system.actorSelection(node(role) / "user" / actorName) ! Identify(actorName)
    expectMsgType[ActorIdentity].ref.get
  }

  def assertCleanup(): Unit = {
    awaitAssert {
      remoteWatcher ! Stats
      expectMsg(Stats.empty)
    }
  }

  "RemoteNodeDeathWatch (" + scenario + ")" must {

    "receive Terminated when remote actor is stopped" taggedAs LongRunningTest in {
      runOn(first) {
        val watcher = system.actorOf(Props(classOf[ProbeActor], testActor), "watcher1")
        enterBarrier("actors-started-1")

        val subject = identify(second, "subject1")
        watcher ! WatchIt(subject)
        expectMsg(1 second, Ack)
        subject ! "hello1"
        enterBarrier("watch-established-1")

        sleep()
        expectMsgType[WrappedTerminated].t.actor must be(subject)
      }

      runOn(second) {
        val subject = system.actorOf(Props(classOf[ProbeActor], testActor), "subject1")
        enterBarrier("actors-started-1")

        expectMsg(3 seconds, "hello1")
        enterBarrier("watch-established-1")

        sleep()
        system.stop(subject)
      }

      enterBarrier("terminated-verified-1")

      // verify that things are cleaned up, and heartbeating is stopped
      assertCleanup()
      expectNoMsg(2.seconds)
      assertCleanup()

      enterBarrier("after-1")
    }

    "cleanup after watch/unwatch" taggedAs LongRunningTest in {
      runOn(first) {
        val watcher = system.actorOf(Props(classOf[ProbeActor], testActor), "watcher2")
        enterBarrier("actors-started-2")

        val subject = identify(second, "subject2")
        watcher ! WatchIt(subject)
        expectMsg(1 second, Ack)
        enterBarrier("watch-2")

        sleep()
        watcher ! UnwatchIt(subject)
        expectMsg(1 second, Ack)
        enterBarrier("unwatch-2")
      }

      runOn(second) {
        system.actorOf(Props(classOf[ProbeActor], testActor), "subject2")
        enterBarrier("actors-started-2")
        enterBarrier("watch-2")
        enterBarrier("unwatch-2")
      }

      // verify that things are cleaned up, and heartbeating is stopped
      assertCleanup()
      expectNoMsg(2.seconds)
      assertCleanup()

      enterBarrier("after-2")
    }

    "cleanup after bi-directional watch/unwatch" taggedAs LongRunningTest in {
      val watcher = system.actorOf(Props(classOf[ProbeActor], testActor), "watcher3")
      system.actorOf(Props(classOf[ProbeActor], testActor), "subject3")
      enterBarrier("actors-started-3")

      val other = if (myself == first) second else first
      val subject = identify(other, "subject3")
      watcher ! WatchIt(subject)
      expectMsg(1 second, Ack)
      enterBarrier("watch-3")

      sleep()
      watcher ! UnwatchIt(subject)
      expectMsg(1 second, Ack)
      enterBarrier("unwatch-3")

      // verify that things are cleaned up, and heartbeating is stopped
      assertCleanup()
      expectNoMsg(2.seconds)
      assertCleanup()

      enterBarrier("after-3")
    }

    "cleanup after bi-directional watch/stop/unwatch" taggedAs LongRunningTest in {
      val watcher1 = system.actorOf(Props(classOf[ProbeActor], testActor), "w1")
      val watcher2 = system.actorOf(Props(classOf[ProbeActor], testActor), "w2")
      system.actorOf(Props(classOf[ProbeActor], testActor), "s1")
      val s2 = system.actorOf(Props(classOf[ProbeActor], testActor), "s2")
      enterBarrier("actors-started-4")

      val other = if (myself == first) second else first
      val subject1 = identify(other, "s1")
      val subject2 = identify(other, "s2")
      watcher1 ! WatchIt(subject1)
      expectMsg(1 second, Ack)
      watcher2 ! WatchIt(subject2)
      expectMsg(1 second, Ack)
      enterBarrier("watch-4")

      sleep()
      watcher1 ! UnwatchIt(subject1)
      expectMsg(1 second, Ack)
      system.stop(s2)
      enterBarrier("unwatch-stop-4")

      expectMsgType[WrappedTerminated].t.actor must be(subject2)

      // verify that things are cleaned up, and heartbeating is stopped
      assertCleanup()
      expectNoMsg(2.seconds)
      assertCleanup()

      enterBarrier("after-4")
    }

    "cleanup after stop" taggedAs LongRunningTest in {
      runOn(first) {
        val p1, p2, p3 = TestProbe()
        val a1 = system.actorOf(Props(classOf[ProbeActor], p1.ref), "a1")
        val a2 = system.actorOf(Props(classOf[ProbeActor], p2.ref), "a2")
        val a3 = system.actorOf(Props(classOf[ProbeActor], p3.ref), "a3")
        enterBarrier("actors-started-5")

        val b1 = identify(second, "b1")
        val b2 = identify(second, "b2")
        val b3 = identify(second, "b3")

        a1 ! WatchIt(b1)
        expectMsg(1 second, Ack)
        a1 ! WatchIt(b2)
        expectMsg(1 second, Ack)
        a2 ! WatchIt(b2)
        expectMsg(1 second, Ack)
        a3 ! WatchIt(b3)
        expectMsg(1 second, Ack)
        sleep()
        a2 ! UnwatchIt(b2)
        expectMsg(1 second, Ack)

        enterBarrier("watch-established-5")

        sleep()
        a1 ! PoisonPill
        a2 ! PoisonPill
        a3 ! PoisonPill

        enterBarrier("stopped-5")
        enterBarrier("terminated-verified-5")

        // verify that things are cleaned up, and heartbeating is stopped
        assertCleanup()
        expectNoMsg(2.seconds)
        assertCleanup()
      }

      runOn(second) {
        val p1, p2, p3 = TestProbe()
        val b1 = system.actorOf(Props(classOf[ProbeActor], p1.ref), "b1")
        val b2 = system.actorOf(Props(classOf[ProbeActor], p2.ref), "b2")
        val b3 = system.actorOf(Props(classOf[ProbeActor], p3.ref), "b3")
        enterBarrier("actors-started-5")

        val a1 = identify(first, "a1")
        val a2 = identify(first, "a2")
        val a3 = identify(first, "a3")

        b1 ! WatchIt(a1)
        expectMsg(1 second, Ack)
        b1 ! WatchIt(a2)
        expectMsg(1 second, Ack)
        b2 ! WatchIt(a2)
        expectMsg(1 second, Ack)
        b3 ! WatchIt(a3)
        expectMsg(1 second, Ack)
        b3 ! WatchIt(a3)
        expectMsg(1 second, Ack)
        sleep()
        b2 ! UnwatchIt(a2)
        expectMsg(1 second, Ack)

        enterBarrier("watch-established-5")
        enterBarrier("stopped-5")

        p1.receiveN(2, 5 seconds).collect { case WrappedTerminated(t) ⇒ t.actor }.toSet must be(Set(a1, a2))
        p3.expectMsgType[WrappedTerminated](5 seconds).t.actor must be(a3)
        p2.expectNoMsg(2 seconds)
        enterBarrier("terminated-verified-5")

        // verify that things are cleaned up, and heartbeating is stopped
        assertCleanup()
        expectNoMsg(2.seconds)
        p1.expectNoMsg(100 millis)
        p2.expectNoMsg(100 millis)
        p3.expectNoMsg(100 millis)
        assertCleanup()
      }

      enterBarrier("after-5")
    }

    "receive Terminated when watched node is shutdown" taggedAs LongRunningTest in {
      runOn(first) {
        val watcher = system.actorOf(Props(classOf[ProbeActor], testActor), "watcher6")
        val watcher2 = system.actorOf(Props(classOf[ProbeActor], system.deadLetters))
        enterBarrier("actors-started-6")

        val subject = identify(second, "subject6")
        watcher ! WatchIt(subject)
        expectMsg(1 second, Ack)
        watcher2 ! WatchIt(subject)
        expectMsg(1 second, Ack)
        subject ! "hello6"

        // testing with this watch/unwatch of watcher2 to make sure that the unwatch doesn't
        // remove the first watch
        watcher2 ! UnwatchIt(subject)
        expectMsg(1 second, Ack)

        enterBarrier("watch-established-6")

        sleep()

        log.info("shutdown second")
        testConductor.shutdown(second, 0).await
        expectMsgType[WrappedTerminated](15 seconds).t.actor must be(subject)

        // verify that things are cleaned up, and heartbeating is stopped
        assertCleanup()
        expectNoMsg(2.seconds)
        assertCleanup()
      }

      runOn(second) {
        system.actorOf(Props(classOf[ProbeActor], testActor), "subject6")
        enterBarrier("actors-started-6")

        expectMsg(3 seconds, "hello6")
        enterBarrier("watch-established-6")
      }

      enterBarrier("after-6")
    }

  }
}
