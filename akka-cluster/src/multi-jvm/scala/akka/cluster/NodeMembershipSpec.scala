/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._

object NodeMembershipMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
    akka.cluster {
      gossip-frequency = 200 ms
      leader-actions-frequency = 200 ms
      periodic-tasks-initial-delay = 300 ms
      # FIXME get rid of this hardcoded host:port
      node-to-join = "akka://MultiNodeSpec@localhost:2602"
    }
    """)))

  nodeConfig(first, ConfigFactory.parseString("""
    # FIXME get rid of this hardcoded port
    akka.remote.netty.port=2602
    """))

}

class NodeMembershipMultiJvmNode1 extends NodeMembershipSpec {
  override var node: Cluster = _
}
class NodeMembershipMultiJvmNode2 extends NodeMembershipSpec {
  override var node: Cluster = _
}
class NodeMembershipMultiJvmNode3 extends NodeMembershipSpec {
  override var node: Cluster = _
}

abstract class NodeMembershipSpec extends MultiNodeSpec(NodeMembershipMultiJvmSpec) with ImplicitSender with BeforeAndAfter {
  import NodeMembershipMultiJvmSpec._

  override def initialParticipants = 3

  var node: Cluster

  after {
    testConductor.enter("after")
  }

  "A set of connected cluster systems" must {

    val firstAddress = testConductor.getAddressFor(first).await
    val secondAddress = testConductor.getAddressFor(second).await
    val thirdAddress = testConductor.getAddressFor(third).await

    "(when two systems) start gossiping to each other so that both systems gets the same gossip info" in {

      def assertMembers: Unit = {
        val members = node.latestGossip.members.toIndexedSeq
        members.size must be(2)
        members(0).address must be(firstAddress)
        members(1).address must be(secondAddress)
        awaitCond {
          node.latestGossip.members.forall(_.status == MemberStatus.Up)
        }
      }

      runOn(first) {
        node = Cluster(system)
        awaitCond(node.latestGossip.members.size == 2)
        assertMembers
        node.convergence.isDefined
      }

      runOn(second) {
        node = Cluster(system)
        awaitCond(node.latestGossip.members.size == 2)
        assertMembers
        node.convergence.isDefined
      }

    }

    "(when three systems) start gossiping to each other so that both systems gets the same gossip info" in {

      def assertMembers: Unit = {
        val members = node.latestGossip.members.toIndexedSeq
        members.size must be(3)
        members(0).address must be(firstAddress)
        members(1).address must be(secondAddress)
        members(2).address must be(thirdAddress)
        awaitCond {
          node.latestGossip.members.forall(_.status == MemberStatus.Up)
        }
      }

      runOn(third) {
        node = Cluster(system)
        awaitCond(node.latestGossip.members.size == 3)
        awaitCond(node.convergence.isDefined)
        assertMembers
      }

      runOn(first) {
        awaitCond(node.latestGossip.members.size == 3)
        assertMembers
        node.convergence.isDefined
      }

      runOn(second) {
        awaitCond(node.latestGossip.members.size == 3)
        assertMembers
        node.convergence.isDefined
      }

    }
  }

}
