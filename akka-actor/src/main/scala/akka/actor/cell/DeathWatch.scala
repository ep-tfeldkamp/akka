/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.cell

import akka.actor.{ Terminated, InternalActorRef, ActorRef, ActorCell, Actor, Address, NodeUnreachable }
import akka.dispatch.{ Watch, Unwatch }
import akka.event.Logging.{ Warning, Error, Debug }
import scala.util.control.NonFatal

private[akka] trait DeathWatch { this: ActorCell ⇒

  private var watching: Set[ActorRef] = ActorCell.emptyActorRefSet
  private var watchedBy: Set[ActorRef] = ActorCell.emptyActorRefSet

  override final def watch(subject: ActorRef): ActorRef = subject match {
    case a: InternalActorRef ⇒
      if (a != self && !watching.contains(a)) {
        // start subscription to NodeUnreachable if non-local subject and not already subscribing
        if (!a.isLocal && !isSubscribingToNodeUnreachable)
          system.eventStream.subscribe(self, classOf[NodeUnreachable])

        a.sendSystemMessage(Watch(a, self)) // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
        watching += a
      }
      a
  }

  override final def unwatch(subject: ActorRef): ActorRef = subject match {
    case a: InternalActorRef ⇒
      if (a != self && watching.contains(a)) {
        a.sendSystemMessage(Unwatch(a, self)) // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
        watching -= a
      }
      a
  }

  /**
   * When this actor is watching the subject of [[akka.actor.Terminated]] message
   * it will be propagated to user's receive.
   */
  protected def watchedActorTerminated(t: Terminated): Unit = if (watching.contains(t.actor)) {
    watching -= t.actor
    receiveMessage(t)
  }

  protected def tellWatchersWeDied(actor: Actor): Unit = {
    if (!watchedBy.isEmpty) {
      val terminated = Terminated(self)(existenceConfirmed = true)
      try {
        watchedBy foreach {
          watcher ⇒
            try watcher.tell(terminated, self) catch {
              case NonFatal(t) ⇒ publish(Error(t, self.path.toString, clazz(actor), "deathwatch"))
            }
        }
      } finally watchedBy = ActorCell.emptyActorRefSet
    }
  }

  protected def unwatchWatchedActors(actor: Actor): Unit = {
    if (!watching.isEmpty) {
      try {
        watching foreach { // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
          case watchee: InternalActorRef ⇒ try watchee.sendSystemMessage(Unwatch(watchee, self)) catch {
            case NonFatal(t) ⇒ publish(Error(t, self.path.toString, clazz(actor), "deathwatch"))
          }
        }
      } finally watching = ActorCell.emptyActorRefSet
    }
  }

  protected def addWatcher(watchee: ActorRef, watcher: ActorRef): Unit = {
    val watcheeSelf = watchee == self
    val watcherSelf = watcher == self

    if (watcheeSelf && !watcherSelf) {
      if (!watchedBy.contains(watcher)) {
        watchedBy += watcher
        if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "now monitoring " + watcher))
      }
    } else if (!watcheeSelf && watcherSelf) {
      watch(watchee)
    } else {
      publish(Warning(self.path.toString, clazz(actor), "BUG: illegal Watch(%s,%s) for %s".format(watchee, watcher, self)))
    }
  }

  protected def remWatcher(watchee: ActorRef, watcher: ActorRef): Unit = {
    val watcheeSelf = watchee == self
    val watcherSelf = watcher == self

    if (watcheeSelf && !watcherSelf) {
      if (watchedBy.contains(watcher)) {
        watchedBy -= watcher
        if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, clazz(actor), "stopped monitoring " + watcher))
      }
    } else if (!watcheeSelf && watcherSelf) {
      unwatch(watchee)
    } else {
      publish(Warning(self.path.toString, clazz(actor), "BUG: illegal Unwatch(%s,%s) for %s".format(watchee, watcher, self)))
    }
  }

  protected def watchedNodeUnreachable(address: Address): Unit = {
    val subjects = watching filter { _.path.address == address }

    // FIXME should we cleanup (remove watchedBy) since we know they are dead?

    // FIXME existenceConfirmed?
    subjects foreach { self ! Terminated(_)(existenceConfirmed = false) }
  }

  private def isSubscribingToNodeUnreachable: Boolean = watching.exists {
    case a: InternalActorRef if !a.isLocal ⇒ true
    case _                                 ⇒ false
  }

}