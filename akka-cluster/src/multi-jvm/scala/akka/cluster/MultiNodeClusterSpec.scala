/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.actor.Address
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeSpec
import akka.util.duration._

object MultiNodeClusterSpec {
  def clusterConfig: Config = ConfigFactory.parseString("""
    akka.cluster {
      gossip-frequency = 200 ms
      leader-actions-frequency = 200 ms
      periodic-tasks-initial-delay = 300 ms
    }
    akka.test {
      single-expect-default = 5 s
    }
    """)
}

trait MultiNodeClusterSpec { self: MultiNodeSpec ⇒

  def cluster: Cluster = Cluster(system)

  /**
   * Assert that the member addresses match the expected addresses in the
   * sort order used by the cluster.
   */
  def assertMembers(gotMembers: Iterable[Member], expectedAddresses: Address*): Unit = {
    import Member.addressOrdering
    val members = gotMembers.toIndexedSeq
    members.size must be(expectedAddresses.length)
    expectedAddresses.sorted.zipWithIndex.foreach { case (a, i) ⇒ members(i).address must be(a) }
  }

  /**
   * Assert that the cluster has elected the correct leader
   * out of all nodes in the cluster. First
   * member in the cluster ring is expected leader.
   */
  def assertLeader(nodesInCluster: RoleName*): Unit = if (nodesInCluster.contains(mySelf)) {
    nodesInCluster.length must not be (0)
    val expectedLeader = roleOfLeader(nodesInCluster)
    cluster.isLeader must be(ifNode(expectedLeader)(true)(false))
  }

  /**
   * Wait until the expected number of members has status Up and convergence has been reached.
   */
  def awaitUpConvergence(numberOfMembers: Int): Unit = {
    awaitCond(cluster.latestGossip.members.size == numberOfMembers)
    awaitCond(cluster.latestGossip.members.forall(_.status == MemberStatus.Up))
    awaitCond(cluster.convergence.isDefined, 10 seconds)
  }

  /**
   * Wait until the expected number of members has status Up and convergence has been reached.
   * Also asserts that nodes in the 'canNotBePartOfRing' are *not* part of the cluster ring.
   */
  def awaitUpConvergence(nrOfMembers: Int, canNotBePartOfRing: Seq[Address] = Seq.empty[Address]): Unit = {
    awaitCond(cluster.latestGossip.members.size == nrOfMembers)
    awaitCond(cluster.latestGossip.members.forall(_.status == MemberStatus.Up))
    awaitCond(canNotBePartOfRing forall (address ⇒ !(cluster.latestGossip.members exists (_.address == address))))
  }

  def roleOfLeader(nodesInCluster: Seq[RoleName]): RoleName = {
    nodesInCluster.length must not be (0)
    nodesInCluster.sorted.head
  }

  /**
   * Sort the roles in the order used by the cluster.
   */
  implicit val clusterOrdering: Ordering[RoleName] = new Ordering[RoleName] {
    import Member.addressOrdering
    def compare(x: RoleName, y: RoleName) = addressOrdering.compare(node(x).address, node(y).address)
  }

  def roleName(address: Address): Option[RoleName] = {
    testConductor.getNodes.await.find(node(_).address == address)
  }
}
