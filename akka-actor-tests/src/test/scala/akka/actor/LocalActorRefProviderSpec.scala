/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import language.postfixOps
import akka.testkit._
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.util.Timeout
import scala.concurrent.Future
import scala.util.Success
import scala.util.Failure

object LocalActorRefProviderSpec {
  val config = """
    akka {
      actor {
        default-dispatcher {
          executor = "thread-pool-executor"
          thread-pool-executor {
            core-pool-size-min = 16
            core-pool-size-max = 16
          }
        }
      }
    }
  """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class LocalActorRefProviderSpec extends AkkaSpec(LocalActorRefProviderSpec.config) {
  "An LocalActorRefProvider" must {

    "find actor refs using actorFor" in {
      val a = system.actorOf(Props(new Actor { def receive = { case _ ⇒ } }))
      val b = system.actorFor(a.path)
      a must be === b
    }

    "find child actor with URL encoded name using actorFor" in {
      val childName = "akka%3A%2F%2FClusterSystem%40127.0.0.1%3A2552"
      val a = system.actorOf(Props(new Actor {
        val child = context.actorOf(Props.empty, name = childName)
        def receive = {
          case "lookup" ⇒
            if (childName == child.path.name) sender ! context.actorFor(childName)
            else sender ! s"$childName is not ${child.path.name}!"
        }
      }))
      a.tell("lookup", testActor)
      val b = expectMsgType[ActorRef]
      b.isTerminated must be(false)
      b.path.name must be(childName)
    }

  }

  "A LocalActorRef's ActorCell" must {
    "not retain its original Props when terminated" in {
      val GetChild = "GetChild"
      val a = watch(system.actorOf(Props(new Actor {
        val child = context.actorOf(Props.empty)
        def receive = { case `GetChild` ⇒ sender ! child }
      })))
      a.tell(GetChild, testActor)
      val child = expectMsgType[ActorRef]
      child.asInstanceOf[LocalActorRef].underlying.props must be theSameInstanceAs Props.empty
      system stop a
      expectMsgType[Terminated]
      val childProps = child.asInstanceOf[LocalActorRef].underlying.props
      childProps must not be theSameInstanceAs(Props.empty)
      childProps must be theSameInstanceAs ActorCell.terminatedProps
    }
  }

  "An ActorRefFactory" must {
    implicit val ec = system.dispatcher
    "only create one instance of an actor with a specific address in a concurrent environment" in {
      val impl = system.asInstanceOf[ActorSystemImpl]
      val provider = impl.provider

      provider.isInstanceOf[LocalActorRefProvider] must be(true)

      for (i ← 0 until 100) {
        val address = "new-actor" + i
        implicit val timeout = Timeout(5 seconds)
        val actors = for (j ← 1 to 4) yield Future(system.actorOf(Props(new Actor { def receive = { case _ ⇒ } }), address))
        val set = Set() ++ actors.map(a ⇒ Await.ready(a, timeout.duration).value match {
          case Some(Success(a: ActorRef)) ⇒ 1
          case Some(Failure(ex: InvalidActorNameException)) ⇒ 2
          case x ⇒ x
        })
        set must be === Set(1, 2)
      }
    }

    "only create one instance of an actor from within the same message invocation" in {
      val supervisor = system.actorOf(Props(new Actor {
        def receive = {
          case "" ⇒
            val a, b = context.actorOf(Props.empty, "duplicate")
        }
      }))
      EventFilter[InvalidActorNameException](occurrences = 1) intercept {
        supervisor ! ""
      }
    }

    "throw suitable exceptions for malformed actor names" in {
      intercept[InvalidActorNameException](system.actorOf(Props.empty, null)).getMessage.contains("null") must be(true)
      intercept[InvalidActorNameException](system.actorOf(Props.empty, "")).getMessage.contains("empty") must be(true)
      intercept[InvalidActorNameException](system.actorOf(Props.empty, "$hallo")).getMessage.contains("conform") must be(true)
      intercept[InvalidActorNameException](system.actorOf(Props.empty, "a%")).getMessage.contains("conform") must be(true)
      intercept[InvalidActorNameException](system.actorOf(Props.empty, "%3")).getMessage.contains("conform") must be(true)
      intercept[InvalidActorNameException](system.actorOf(Props.empty, "%1t")).getMessage.contains("conform") must be(true)
      intercept[InvalidActorNameException](system.actorOf(Props.empty, "a?")).getMessage.contains("conform") must be(true)
      intercept[InvalidActorNameException](system.actorOf(Props.empty, "üß")).getMessage.contains("conform") must be(true)
    }

  }
}
