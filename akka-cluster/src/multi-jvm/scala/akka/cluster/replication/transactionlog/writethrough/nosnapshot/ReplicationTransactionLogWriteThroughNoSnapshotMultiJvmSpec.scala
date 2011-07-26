/*
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.replication.transactionlog.writethrough.nosnapshot
import akka.actor._
import akka.cluster._
import Cluster._
import akka.config.Config

object ReplicationTransactionLogWriteThroughNoSnapshotMultiJvmSpec {
  var NrOfNodes = 2

  sealed trait TransactionLogMessage extends Serializable
  case class Count(nr: Int) extends TransactionLogMessage
  case class Log(full: String) extends TransactionLogMessage
  case object GetLog extends TransactionLogMessage

  class HelloWorld extends Actor with Serializable {
    var log = ""
    def receive = {
      case Count(nr) ⇒
        log += nr.toString
        self.reply("World from node [" + Config.nodename + "]")
      case GetLog ⇒
        self.reply(Log(log))
    }
  }
}

class ReplicationTransactionLogWriteThroughNoSnapshotMultiJvmNode1 extends ClusterTestNode {
  import ReplicationTransactionLogWriteThroughNoSnapshotMultiJvmSpec._

  "A cluster" must {

    "be able to replicate an actor with a transaction log and replay transaction log after actor migration" in {

      barrier("start-node1", NrOfNodes) {
        node.start()
      }

      barrier("create-actor-on-node1", NrOfNodes) {
        val actorRef = Actor.actorOf[HelloWorld]("hello-world").start()
        node.isInUseOnNode("hello-world") must be(true)
        actorRef.address must be("hello-world")
        for (i ← 0 until 10)
          (actorRef ? Count(i)).as[String] must be(Some("World from node [node1]"))
      }

      barrier("start-node2", NrOfNodes) {
      }

      node.shutdown()
    }
  }
}

class ReplicationTransactionLogWriteThroughNoSnapshotMultiJvmNode2 extends MasterClusterTestNode {
  import ReplicationTransactionLogWriteThroughNoSnapshotMultiJvmSpec._

  val testNodes = NrOfNodes

  "A cluster" must {

    "be able to replicate an actor with a transaction log and replay transaction log after actor migration" in {

      barrier("start-node1", NrOfNodes) {
      }

      barrier("create-actor-on-node1", NrOfNodes) {
      }

      barrier("start-node2", NrOfNodes) {
        node.start()
      }

      Thread.sleep(5000) // wait for fail-over from node1 to node2

      barrier("check-fail-over-to-node2", NrOfNodes - 1) {
        // both remaining nodes should now have the replica
        node.isInUseOnNode("hello-world") must be(true)
        val actorRef = Actor.registry.local.actorFor("hello-world").getOrElse(fail("Actor should have been in the local actor registry"))
        actorRef.address must be("hello-world")
        (actorRef ? GetLog).as[Log].get must be(Log("0123456789"))
      }

      node.shutdown()
    }
  }

  override def onReady() {
    LocalBookKeeperEnsemble.start()
  }

  override def onShutdown() {
    TransactionLog.shutdown()
    LocalBookKeeperEnsemble.shutdown()
  }
}
