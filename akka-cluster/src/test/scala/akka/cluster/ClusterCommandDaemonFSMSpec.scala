/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit._
import akka.actor.Address

class ClusterCommandDaemonFSMSpec
  extends AkkaSpec("akka.actor.provider = akka.remote.RemoteActorRefProvider")
  with ImplicitSender {

  "A ClusterCommandDaemon FSM" must {
    val address = Address("akka", system.name)
    "start in Joining" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
    }
    "be able to switch from Joining to Up" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Up(address)
      fsm.stateName must be(MemberStatus.Up)
    }
    "be able to switch from Joining to Down" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Down(address)
      fsm.stateName must be(MemberStatus.Down)
    }
    "be able to switch from Joining to Removed" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Remove(address)
      fsm.stateName must be(MemberStatus.Removed)
    }
    "be able to switch from Up to Down" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Up(address)
      fsm.stateName must be(MemberStatus.Up)
      fsm ! ClusterAction.Down(address)
      fsm.stateName must be(MemberStatus.Down)
    }
    "be able to switch from Up to Leaving" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Up(address)
      fsm.stateName must be(MemberStatus.Up)
      fsm ! ClusterAction.Leave(address)
      fsm.stateName must be(MemberStatus.Leaving)
    }
    "be able to switch from Up to Exiting" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Up(address)
      fsm.stateName must be(MemberStatus.Up)
      fsm ! ClusterAction.Exit(address)
      fsm.stateName must be(MemberStatus.Exiting)
    }
    "be able to switch from Up to Removed" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Up(address)
      fsm.stateName must be(MemberStatus.Up)
      fsm ! ClusterAction.Remove(address)
      fsm.stateName must be(MemberStatus.Removed)
    }
    "be able to switch from Leaving to Down" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Up(address)
      fsm.stateName must be(MemberStatus.Up)
      fsm ! ClusterAction.Leave(address)
      fsm.stateName must be(MemberStatus.Leaving)
      fsm ! ClusterAction.Down(address)
      fsm.stateName must be(MemberStatus.Down)
    }
    "be able to switch from Leaving to Removed" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Up(address)
      fsm.stateName must be(MemberStatus.Up)
      fsm ! ClusterAction.Leave(address)
      fsm.stateName must be(MemberStatus.Leaving)
      fsm ! ClusterAction.Remove(address)
      fsm.stateName must be(MemberStatus.Removed)
    }
    "be able to switch from Exiting to Removed" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Up(address)
      fsm.stateName must be(MemberStatus.Up)
      fsm ! ClusterAction.Exit(address)
      fsm.stateName must be(MemberStatus.Exiting)
      fsm ! ClusterAction.Remove(address)
      fsm.stateName must be(MemberStatus.Removed)
    }
    "be able to switch from Down to Removed" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Up(address)
      fsm.stateName must be(MemberStatus.Up)
      fsm ! ClusterAction.Down(address)
      fsm.stateName must be(MemberStatus.Down)
      fsm ! ClusterAction.Remove(address)
      fsm.stateName must be(MemberStatus.Removed)
    }
    "not be able to switch from Removed to any other state" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Up(address)
      fsm.stateName must be(MemberStatus.Up)
      fsm ! ClusterAction.Remove(address)
      fsm.stateName must be(MemberStatus.Removed)
      fsm ! ClusterAction.Up(address)
      fsm.stateName must be(MemberStatus.Removed)
      fsm ! ClusterAction.Leave(address)
      fsm.stateName must be(MemberStatus.Removed)
      fsm ! ClusterAction.Down(address)
      fsm.stateName must be(MemberStatus.Removed)
      fsm ! ClusterAction.Exit(address)
      fsm.stateName must be(MemberStatus.Removed)
      fsm ! ClusterAction.Remove(address)
      fsm.stateName must be(MemberStatus.Removed)
    }

    "remain in the same state when receiving a Join command" in {
      val fsm = TestFSMRef(new ClusterCommandDaemon)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Join(address)
      fsm.stateName must be(MemberStatus.Joining)
      fsm ! ClusterAction.Up(address)
      fsm.stateName must be(MemberStatus.Up)
      fsm ! ClusterAction.Join(address)
      fsm.stateName must be(MemberStatus.Up)
      fsm ! ClusterAction.Leave(address)
      fsm.stateName must be(MemberStatus.Leaving)
      fsm ! ClusterAction.Join(address)
      fsm.stateName must be(MemberStatus.Leaving)
      fsm ! ClusterAction.Down(address)
      fsm.stateName must be(MemberStatus.Down)
      fsm ! ClusterAction.Join(address)
      fsm.stateName must be(MemberStatus.Down)
    }
  }
}
