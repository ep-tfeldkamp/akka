/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor.InternalActorRef
import akka.actor.Terminated
import akka.actor.Actor
import akka.actor.ActorRef
import akka.dispatch.sysmsg.DeathWatchNotification

/**
 * INTERNAL API
 */
private[akka] object RemoteDeploymentWatcher {
  case class WatchRemote(actor: ActorRef, supervisor: ActorRef)
}

/**
 * INTERNAL API
 *
 * Responsible for cleaning up child references of remote deployed actors when remote node
 * goes down (jvm crash, network failure), i.e. triggered by [[akka.actor.AddressTerminated]].
 */
private[akka] class RemoteDeploymentWatcher extends Actor {
  import RemoteDeploymentWatcher._
  var supervisors = Map.empty[ActorRef, InternalActorRef]

  def receive = {
    case WatchRemote(a, supervisor: InternalActorRef) ⇒
      supervisors += (a -> supervisor)
      context.watch(a)

    case t @ Terminated(a) if supervisors isDefinedAt a ⇒
      // send extra DeathWatchNotification to the supervisor so that it will remove the child
      supervisors(a).sendSystemMessage(DeathWatchNotification(a, existenceConfirmed = false, addressTerminated = true))
      supervisors -= a

    case _: Terminated ⇒
  }
}