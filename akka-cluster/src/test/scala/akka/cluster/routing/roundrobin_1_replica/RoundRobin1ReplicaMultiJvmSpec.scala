/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.cluster.routing.roundrobin_1_replica

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterAll

import akka.cluster._
import Cluster._
import akka.actor._
import akka.actor.Actor._
import akka.config.Config

/**
 * Test that if a single node is used with a round robin router with replication factor then the actor is instantiated on the single node.
 */
object RoundRobin1ReplicaMultiJvmSpec {

  class HelloWorld extends Actor with Serializable {
    def receive = {
      case "Hello" ⇒
        self.reply("World from node [" + Config.nodename + "]")
    }
  }
}

class RoundRobin1ReplicaMultiJvmNode1 extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import RoundRobin1ReplicaMultiJvmSpec._

  "A cluster" must {

    "create clustered actor, get a 'local' actor on 'home' node and a 'ref' to actor on remote node" in {
      node.start()

      var hello: ActorRef = null
      hello = Actor.actorOf[HelloWorld]("service-hello")
      hello must not equal (null)
      hello.address must equal("service-hello")
      hello.isInstanceOf[ClusterActorRef] must be(true)

      hello must not equal (null)
      val reply = (hello ? "Hello").as[String].getOrElse(fail("Should have recieved reply from node1"))
      reply must equal("World from node [node1]")

      node.shutdown()
    }
  }

  override def beforeAll() {
    startLocalCluster()
  }

  override def afterAll() {
    shutdownLocalCluster()
  }
}
