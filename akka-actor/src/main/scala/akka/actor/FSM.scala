/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import akka.util._

import scala.collection.mutable
import java.util.concurrent.{ScheduledFuture, TimeUnit}

object FSM {

  case class Event[D](event: Any, stateData: D)

  case class Transition[S](from: S, to: S)
  case class SubscribeTransitionCallBack(actorRef: ActorRef)
  case class UnsubscribeTransitionCallBack(actorRef: ActorRef)

  sealed trait Reason
  case object Normal extends Reason
  case object Shutdown extends Reason
  case class Failure(cause: Any) extends Reason
  case class StopEvent[S, D](reason: Reason, currentState: S, stateData: D)

  case object StateTimeout
  case class TimeoutMarker(generation: Long)

  case class Timer(name: String, msg: AnyRef, repeat: Boolean) {
    private var ref: Option[ScheduledFuture[AnyRef]] = _

    def schedule(actor: ActorRef, timeout: Duration) {
      if (repeat) {
        ref = Some(Scheduler.schedule(actor, this, timeout.length, timeout.length, timeout.unit))
      } else {
        ref = Some(Scheduler.scheduleOnce(actor, this, timeout.length, timeout.unit))
      }
    }

    def cancel {
      ref = ref flatMap {
        t => t.cancel(true); None
      }
    }
  }

  /*
  * With these implicits in scope, you can write "5 seconds" anywhere a
  * Duration or Option[Duration] is expected. This is conveniently true
  * for derived classes.
  */
  implicit def d2od(d: Duration): Option[Duration] = Some(d)
  implicit def p2od(p: (Long, TimeUnit)): Duration = new Duration(p._1, p._2)
}

/**
 * Finite State Machine actor trait. Use as follows:
 *
 * <pre>
 *   object A {
 *     trait State
 *     case class One extends State
 *     case class Two extends State
 *
 *     case class Data(i : Int)
 *   }
 *
 *   class A extends Actor with FSM[A.State, A.Data] {
 *     import A._
 *
 *     startWith(One, Data(42))
 *     when(One) { [some partial function] }
 *     when(Two, stateTimeout = 5 seconds) { ... }
 *     initialize
 *   }
 * </pre>
 *
 * Within the partial function the following values are returned for effecting
 * state transitions:
 *
 *  - <code>stay</code> for staying in the same state
 *  - <code>stay using Data(...)</code> for staying in the same state, but with
 *    different data
 *  - <code>stay forMax 5.millis</code> for staying with a state timeout; can be
 *    combined with <code>using</code>
 *  - <code>goto(...)</code> for changing into a different state; also supports
 *    <code>using</code> and <code>forMax</code>
 *  - <code>stop</code> for terminating this FSM actor
 *
 * Each of the above also supports the method <code>replying(AnyRef)</code> for
 * sending a reply before changing state.
 *
 * Another feature is that other actors may subscribe for transition events by
 * sending a <code>SubscribeTransitionCallback</code> message to this actor;
 * use <code>UnsubscribeTransitionCallback</code> before stopping the other
 * actor.
 *
 * State timeouts set an upper bound to the time which may pass before another
 * message is received in the current state. If no external message is
 * available, then upon expiry of the timeout a StateTimeout message is sent.
 * Note that this message will only be received in the state for which the
 * timeout was set and that any message received will cancel the timeout
 * (possibly to be started again by the next transition).
 *
 * Another feature is the ability to install and cancel single-shot as well as
 * repeated timers which arrange for the sending of a user-specified message:
 *
 * <pre>
 *   setTimer("tock", TockMsg, 1 second, true) // repeating
 *   setTimer("lifetime", TerminateMsg, 1 hour, false) // single-shot
 *   cancelTimer("tock")
 *   timerActive_? ("tock")
 * </pre>
 */
trait FSM[S, D] {
  this: Actor =>

  import FSM._

  type StateFunction = scala.PartialFunction[Event[D], State]
  type Timeout = Option[Duration]

  /* DSL */

  /**
   * Insert a new StateFunction at the end of the processing chain for the
   * given state. If the stateTimeout parameter is set, entering this state
   * without a differing explicit timeout setting will trigger a StateTimeout
   * event; the same is true when using #stay.
   *
   * @param stateName designator for the state
   * @param stateTimeout default state timeout for this state
   * @param stateFunction partial function describing response to input
   */
  protected final def when(stateName: S, stateTimeout: Timeout = None)(stateFunction: StateFunction) = {
    register(stateName, stateFunction, stateTimeout)
  }

  /**
   * Set initial state. Call this method from the constructor before the #initialize method.
   *
   * @param stateName initial state designator
   * @param stateData initial state data
   * @param timeout state timeout for the initial state, overriding the default timeout for that state
   */
  protected final def startWith(stateName: S,
                                stateData: D,
                                timeout: Timeout = None) = {
    currentState = State(stateName, stateData, timeout)
  }

  /**
   * Produce transition to other state. Return this from a state function in
   * order to effect the transition.
   *
   * @param nextStateName state designator for the next state
   * @return state transition descriptor
   */
  protected final def goto(nextStateName: S): State = {
    State(nextStateName, currentState.stateData)
  }

  /**
   * Produce "empty" transition descriptor. Return this from a state function
   * when no state change is to be effected.
   *
   * @return descriptor for staying in current state
   */
  protected final def stay(): State = {
    // cannot directly use currentState because of the timeout field
    goto(currentState.stateName)
  }

  /**
   * Produce change descriptor to stop this FSM actor with reason "Normal".
   */
  protected final def stop(): State = {
    stop(Normal)
  }

  /**
   * Produce change descriptor to stop this FSM actor including specified reason.
   */
  protected final def stop(reason: Reason): State = {
    stop(reason, currentState.stateData)
  }

  /**
   * Produce change descriptor to stop this FSM actor including specified reason.
   */
  protected final def stop(reason: Reason, stateData: D): State = {
    stay using stateData withStopReason (reason)
  }

  /**
   * Schedule named timer to deliver message after given delay, possibly repeating.
   * @param name identifier to be used with cancelTimer()
   * @param msg message to be delivered
   * @param timeout delay of first message delivery and between subsequent messages
   * @param repeat send once if false, scheduleAtFixedRate if true
   * @return current state descriptor
   */
  protected final def setTimer(name: String, msg: AnyRef, timeout: Duration, repeat: Boolean): State = {
    if (timers contains name) {
      timers(name).cancel
    }
    val timer = Timer(name, msg, repeat)
    timer.schedule(self, timeout)
    timers(name) = timer
    stay
  }

  /**
   * Cancel named timer, ensuring that the message is not subsequently delivered (no race).
   * @param name of the timer to cancel
   */
  protected final def cancelTimer(name: String) = {
    if (timers contains name) {
      timers(name).cancel
      timers -= name
    }
  }

  /**
   * Inquire whether the named timer is still active. Returns true unless the
   * timer does not exist, has previously been canceled or if it was a
   * single-shot timer whose message was already received.
   */
  protected final def timerActive_?(name: String) = timers contains name

  /**
   * Set handler which is called upon each state transition, i.e. not when
   * staying in the same state.
   */
  protected final def onTransition(transitionHandler: PartialFunction[Transition[S], Unit]) = {
    transitionEvent = transitionHandler
  }

  /**
   * Set handler which is called upon termination of this FSM actor.
   */
  protected final def onTermination(terminationHandler: PartialFunction[StopEvent[S,D], Unit]) = {
    terminateEvent = terminationHandler
  }

  /**
   * Set handler which is called upon reception of unhandled messages.
   */
  protected final def whenUnhandled(stateFunction: StateFunction) = {
    handleEvent = stateFunction
  }

  /**
   * Verify existence of initial state and setup timers. This should be the
   * last call within the constructor.
   */
  def initialize {
    makeTransition(currentState)
  }

  /**FSM State data and default handlers */
  private var currentState: State = _
  private var timeoutFuture: Option[ScheduledFuture[AnyRef]] = None
  private var generation: Long = 0L

  private var transitionCallBackList: List[ActorRef] = Nil

  private val timers = mutable.Map[String, Timer]()

  private val stateFunctions = mutable.Map[S, StateFunction]()
  private val stateTimeouts = mutable.Map[S, Timeout]()

  private def register(name: S, function: StateFunction, timeout: Timeout) {
    if (stateFunctions contains name) {
      stateFunctions(name) = stateFunctions(name) orElse function
      stateTimeouts(name) = timeout orElse stateTimeouts(name)
    } else {
      stateFunctions(name) = function
      stateTimeouts(name) = timeout
    }
  }

  private var handleEvent: StateFunction = {
    case Event(value, stateData) =>
      log.slf4j.warn("Event {} not handled in state {}, staying at current state", value, currentState.stateName)
      stay
  }

  private var terminateEvent: PartialFunction[StopEvent[S,D], Unit] = {
    case StopEvent(Failure(cause), _, _) =>
      log.slf4j.error("Stopping because of a failure with cause {}", cause)
    case StopEvent(reason, _, _) => log.slf4j.info("Stopping because of reason: {}", reason)
  }

  private var transitionEvent: PartialFunction[Transition[S], Unit] = {
    case Transition(from, to) => log.slf4j.debug("Transitioning from state {} to {}", from, to)
  }

  override final protected def receive: Receive = {
    case TimeoutMarker(gen) =>
      if (generation == gen) {
        processEvent(StateTimeout)
      }
    case t@Timer(name, msg, repeat) =>
      if (timerActive_?(name)) {
        processEvent(msg)
        if (!repeat) {
          timers -= name
        }
      }
    case SubscribeTransitionCallBack(actorRef) =>
    // send current state back as reference point
      actorRef ! currentState.stateName
      transitionCallBackList ::= actorRef
    case UnsubscribeTransitionCallBack(actorRef) =>
      transitionCallBackList = transitionCallBackList.filterNot(_ == actorRef)
    case value => {
      timeoutFuture = timeoutFuture.flatMap{
        ref => ref.cancel(true); None
      }
      generation += 1
      processEvent(value)
    }
  }

  private def processEvent(value: Any) = {
    val event = Event(value, currentState.stateData)
    val nextState = (stateFunctions(currentState.stateName) orElse handleEvent).apply(event)
    nextState.stopReason match {
      case Some(reason) => terminate(reason)
      case None => makeTransition(nextState)
    }
  }

  private def makeTransition(nextState: State) = {
    if (!stateFunctions.contains(nextState.stateName)) {
      terminate(Failure("Next state %s does not exist".format(nextState.stateName)))
    } else {
      if (currentState.stateName != nextState.stateName) {
        val transition = Transition(currentState.stateName, nextState.stateName)
        transitionEvent.apply(transition)
        transitionCallBackList.foreach(_ ! transition)
      }
      applyState(nextState)
    }
  }

  private def applyState(nextState: State) = {
    currentState = nextState
    currentState.timeout orElse stateTimeouts(currentState.stateName) foreach {
      t =>
        if (t.length >= 0) {
          timeoutFuture = Some(Scheduler.scheduleOnce(self, TimeoutMarker(generation), t.length, t.unit))
        }
    }
  }

  private def terminate(reason: Reason) = {
    terminateEvent.apply(StopEvent(reason, currentState.stateName, currentState.stateData))
    self.stop
  }


  case class State(stateName: S, stateData: D, timeout: Timeout = None) {

    /**
     * Modify state transition descriptor to include a state timeout for the
     * next state. This timeout overrides any default timeout set for the next
     * state.
     */
    def forMax(timeout: Duration): State = {
      copy(timeout = Some(timeout))
    }

    /**
     * Send reply to sender of the current message, if available.
     *
     * @return this state transition descriptor
     */
    def replying(replyValue: Any): State = {
      self.sender match {
        case Some(sender) => sender ! replyValue
        case None => log.slf4j.error("Unable to send reply value {}, no sender reference to reply to", replyValue)
      }
      this
    }

    /**
     * Modify state transition descriptor with new state data. The data will be
     * set when transitioning to the new state.
     */
    def using(nextStateDate: D): State = {
      copy(stateData = nextStateDate)
    }

    private[akka] var stopReason: Option[Reason] = None

    private[akka] def withStopReason(reason: Reason): State = {
      stopReason = Some(reason)
      this
    }
  }

}
