/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

/**
 * == Commonly Used Patterns With Akka ==
 *
 * This package is used as a collection point for usage patterns which involve
 * actors, futures, etc. but are loosely enough coupled to (multiple of) them
 * to present them separately from the core implementation. Currently supported
 * are:
 *
 * <ul>
 * <li><b>ask:</b> create a temporary one-off actor for receiving a reply to a
 * message and complete a [[akka.dispatch.Future]] with it; returns said
 * Future.</li>
 * <li><b>pipeTo:</b> feed eventually computed value of a future to an actor as
 * a message.</li>
 * </ul>
 *
 * In Scala the recommended usage is to import the pattern from the package
 * object:
 * {{{
 * import akka.pattern.ask
 *
 * ask(actor, message) // use it directly
 * actor ask message   // use it by implicit conversion
 * }}}
 *
 * For Java the patterns are available as static methods of the [[akka.pattern.Patterns]]
 * class:
 * {{{
 * import static akka.pattern.Patterns.ask;
 *
 * ask(actor, message);
 * }}}
 */
package object pattern {

  import akka.actor.{ ActorRef, InternalActorRef, ActorRefWithProvider }
  import akka.dispatch.{ Future, Promise }
  import akka.util.Timeout

  /**
   * Import this implicit conversion to gain `?` and `ask` methods on
   * [[akka.actor.ActorRef]], which will defer to the
   * `ask(actorRef, message)(timeout)` method defined here.
   *
   * {{{
   * import akka.pattern.ask
   *
   * val future = actor ? message             // => ask(actor, message)
   * val future = actor ask message           // => ask(actor, message)
   * val future = actor.ask(message)(timeout) // => ask(actor, message)(timeout)
   * }}}
   *
   * All of the above use an implicit [[akka.actor.Timeout]].
   */
  implicit def ask(actorRef: ActorRef): AskSupport.AskableActorRef = new AskSupport.AskableActorRef(actorRef)

  /**
   * Sends a message asynchronously and returns a [[akka.dispatch.Future]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided. The Future
   * will be completed with an [[akka.actor.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`).
   *
   * <b>Warning:</b>
   * When using future callbacks, inside actors you need to carefully avoid closing over
   * the containing actor’s object, i.e. do not call methods or access mutable state
   * on the enclosing actor from within the callback. This would break the actor
   * encapsulation and may introduce synchronization bugs and race conditions because
   * the callback will be scheduled concurrently to the enclosing actor. Unfortunately
   * there is not yet a way to detect these illegal accesses at compile time.
   *
   * <b>Recommended usage:</b>
   *
   * {{{
   *   val f = ask(worker, request)(timeout)
   *   flow {
   *     EnrichedRequest(request, f())
   *   } pipeTo nextActor
   * }}}
   *
   * [see [[akka.dispatch.Future]] for a description of `flow`]
   */
  def ask(actorRef: ActorRef, message: Any)(implicit timeout: Timeout = null): Future[Any] = actorRef match {
    case ref: ActorRefWithProvider ⇒
      val provider = ref.provider
      (if (timeout == null) provider.settings.ActorTimeout else timeout) match {
        case t if t.duration.length <= 0 ⇒
          actorRef.tell(message)
          Promise.failed(new AskTimeoutException("failed to create PromiseActorRef"))(provider.dispatcher)
        case t ⇒
          val a = AskSupport.createAsker(provider, t)
          actorRef.tell(message, a)
          a.result
      }
    case _ ⇒ throw new IllegalArgumentException("incompatible ActorRef " + actorRef)
  }

  /**
   * Import this implicit conversion to gain the `pipeTo` method on [[akka.dispatch.Future]]:
   *
   * {{{
   * import akka.pattern.pipeTo
   *
   * Future { doExpensiveCalc() } pipeTo nextActor
   * }}}
   */
  implicit def pipeTo[T](future: Future[T]): PipeToSupport.PipeableFuture[T] = new PipeToSupport.PipeableFuture(future)

  /**
   * Register an onComplete callback on this [[akka.dispatch.Future]] to send
   * the result to the given actor reference. Returns the original Future to
   * allow method chaining.
   *
   * <b>Recommended usage example:</b>
   *
   * {{{
   *   val f = ask(worker, request)(timeout)
   *   flow {
   *     EnrichedRequest(request, f())
   *   } pipeTo nextActor
   * }}}
   *
   * [see [[akka.dispatch.Future]] for a description of `flow`]
   */
  def pipeTo[T](future: Future[T], actorRef: ActorRef): Future[T] = {
    future onComplete {
      case Right(r) ⇒ actorRef ! r
      case Left(f)  ⇒ actorRef ! akka.actor.Status.Failure(f)
    }
    future
  }

}
