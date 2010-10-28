/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.config

import akka.actor.{ActorRef}
import akka.dispatch.MessageDispatcher

case class RemoteAddress(val hostname: String, val port: Int)

/**
 * Configuration classes - not to be used as messages.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Supervision {
  sealed abstract class ConfigElement

  abstract class Server extends ConfigElement
  sealed abstract class LifeCycle extends ConfigElement
  sealed abstract class FaultHandlingStrategy(val trapExit: List[Class[_ <: Throwable]]) extends ConfigElement

  case class SupervisorConfig(restartStrategy: FaultHandlingStrategy, worker: List[Server]) extends Server {
    //Java API
    def this(restartStrategy: FaultHandlingStrategy, worker: Array[Server]) = this(restartStrategy,worker.toList)
  }

  class Supervise(val actorRef: ActorRef, val lifeCycle: LifeCycle, val remoteAddress: Option[RemoteAddress]) extends Server {
    //Java API
    def this(actorRef: ActorRef, lifeCycle: LifeCycle, remoteAddress: RemoteAddress) =
      this(actorRef, lifeCycle, Option(remoteAddress))

    //Java API
    def this(actorRef: ActorRef, lifeCycle: LifeCycle) =
      this(actorRef, lifeCycle, None)
  }
  
  object Supervise {
    def apply(actorRef: ActorRef, lifeCycle: LifeCycle, remoteAddress: RemoteAddress) = new Supervise(actorRef, lifeCycle, remoteAddress)
    def apply(actorRef: ActorRef, lifeCycle: LifeCycle) = new Supervise(actorRef, lifeCycle, None)
    def unapply(supervise: Supervise) = Some((supervise.actorRef, supervise.lifeCycle, supervise.remoteAddress))
  }

  object AllForOneStrategy {
    def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int): AllForOneStrategy =
      new AllForOneStrategy(trapExit,
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  }

  case class AllForOneStrategy(override val trapExit: List[Class[_ <: Throwable]],
                               maxNrOfRetries: Option[Int] = None,
                               withinTimeRange: Option[Int] = None) extends FaultHandlingStrategy(trapExit) {
    def this(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
      this(trapExit,
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

    def this(trapExit: Array[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
      this(trapExit.toList,
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

    def this(trapExit: java.util.List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
      this(trapExit.toArray.toList.asInstanceOf[List[Class[_ <: Throwable]]],
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  }

  object OneForOneStrategy {
    def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int): OneForOneStrategy =
      new OneForOneStrategy(trapExit,
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  }

  case class OneForOneStrategy(override val trapExit: List[Class[_ <: Throwable]],
                               maxNrOfRetries: Option[Int] = None,
                               withinTimeRange: Option[Int] = None) extends FaultHandlingStrategy(trapExit) {
    def this(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
      this(trapExit,
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

    def this(trapExit: Array[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
      this(trapExit.toList,
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

    def this(trapExit: java.util.List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
      this(trapExit.toArray.toList.asInstanceOf[List[Class[_ <: Throwable]]],
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  }

  case object NoFaultHandlingStrategy extends FaultHandlingStrategy(Nil)

  //Scala API
  case object Permanent extends LifeCycle
  case object Temporary extends LifeCycle
  case object UndefinedLifeCycle extends LifeCycle

  //Java API (& Scala if you fancy)
  def permanent():          LifeCycle = Permanent
  def temporary():          LifeCycle = Temporary
  def undefinedLifeCycle(): LifeCycle = UndefinedLifeCycle

  //Java API
  def noFaultHandlingStrategy = NoFaultHandlingStrategy

  case class SuperviseTypedActor(_intf: Class[_],
                  val target: Class[_],
                  val lifeCycle: LifeCycle,
                  val timeout: Long,
                  val transactionRequired: Boolean,
                  _dispatcher: MessageDispatcher, // optional
                  _remoteAddress: RemoteAddress   // optional
          ) extends Server {
    val intf: Option[Class[_]] = Option(_intf)
    val dispatcher: Option[MessageDispatcher] = Option(_dispatcher)
    val remoteAddress: Option[RemoteAddress] = Option(_remoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long) =
      this(null: Class[_], target, lifeCycle, timeout, false, null.asInstanceOf[MessageDispatcher], null: RemoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long) =
      this(intf, target, lifeCycle, timeout, false, null.asInstanceOf[MessageDispatcher], null: RemoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long, dispatcher: MessageDispatcher) =
      this(intf, target, lifeCycle, timeout, false, dispatcher, null)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, dispatcher: MessageDispatcher) =
      this(null: Class[_], target, lifeCycle, timeout, false, dispatcher, null:RemoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long, remoteAddress: RemoteAddress) =
      this(intf, target, lifeCycle, timeout, false, null: MessageDispatcher, remoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, remoteAddress: RemoteAddress) =
      this(null: Class[_], target, lifeCycle, timeout, false, null, remoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long, dispatcher: MessageDispatcher, remoteAddress: RemoteAddress) =
      this(intf, target, lifeCycle, timeout, false, dispatcher, remoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, dispatcher: MessageDispatcher, remoteAddress: RemoteAddress) =
      this(null: Class[_], target, lifeCycle, timeout, false, dispatcher, remoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean) =
      this(intf, target, lifeCycle, timeout, transactionRequired, null: MessageDispatcher, null: RemoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean) =
      this(null: Class[_], target, lifeCycle, timeout, transactionRequired, null: MessageDispatcher, null: RemoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean, dispatcher: MessageDispatcher) =
      this(intf, target, lifeCycle, timeout, transactionRequired, dispatcher, null: RemoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean, dispatcher: MessageDispatcher) =
      this(null: Class[_], target, lifeCycle, timeout, transactionRequired, dispatcher, null: RemoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean, remoteAddress: RemoteAddress) =
      this(intf, target, lifeCycle, timeout, transactionRequired, null: MessageDispatcher, remoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean, remoteAddress: RemoteAddress) =
      this(null: Class[_], target, lifeCycle, timeout, transactionRequired, null: MessageDispatcher, remoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean, dispatcher: MessageDispatcher, remoteAddress: RemoteAddress) =
      this(null: Class[_], target, lifeCycle, timeout, transactionRequired, dispatcher, remoteAddress)
  }
}