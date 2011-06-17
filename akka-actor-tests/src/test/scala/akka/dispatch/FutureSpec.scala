package akka.dispatch

import org.scalatest.junit.JUnitSuite
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.prop.Checkers
import org.scalacheck._
import org.scalacheck.Arbitrary._
import org.scalacheck.Prop._
import org.scalacheck.Gen._

import akka.actor.{ Actor, ActorRef }
import Actor._
import org.multiverse.api.latches.StandardLatch
import java.util.concurrent.{ TimeUnit, CountDownLatch }

object FutureSpec {
  class TestActor extends Actor {
    def receive = {
      case "Hello" ⇒
        self.reply("World")
      case "NoReply" ⇒ {}
      case "Failure" ⇒
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }

  class TestDelayActor(await: StandardLatch) extends Actor {
    def receive = {
      case "Hello" ⇒
        await.await
        self.reply("World")
      case "NoReply" ⇒ { await.await }
      case "Failure" ⇒
        await.await
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }
}

class JavaFutureSpec extends JavaFutureTests with JUnitSuite

class FutureSpec extends WordSpec with MustMatchers with Checkers {
  import FutureSpec._

  "A Promise" when {
    "never completed" must {
      behave like emptyFuture(_(Promise()))
    }
    "completed with a result" must {
      val result = "test value"
      val future = Promise[String]().complete(Right(result))
      behave like futureWithResult(_(future, result))
    }
    "completed with an exception" must {
      val message = "Expected Exception"
      val future = Promise[String]().complete(Left(new RuntimeException(message)))
      behave like futureWithException[RuntimeException](_(future, message))
    }
    "expired" must {
      behave like expiredFuture(_(Promise(0)))
    }
  }

  "A Future" when {
    "awaiting a result" that {
      "is not completed" must {
        behave like emptyFuture { test ⇒
          val latch = new StandardLatch
          val result = "test value"
          val future = Future {
            latch.await
            result
          }
          test(future)
          latch.open
          future.await
        }
      }
      "is completed" must {
        behave like futureWithResult { test ⇒
          val latch = new StandardLatch
          val result = "test value"
          val future = Future {
            latch.await
            result
          }
          latch.open
          future.await
          test(future, result)
        }
      }
      "has actions applied" must {
        "pass checks" in {
          check({ (future: Future[Int], actions: List[FutureAction]) ⇒
            val result = (future /: actions)(_ /: _)
            val expected = (future.await.value.get /: actions)(_ /: _)
            classify(expected.isRight, "Result", "Exception") {
              result.await.value.get.toString == expected.toString
            }
          }, maxSize(10000))
        }
      }
    }

    "from an Actor" that {
      "returns a result" must {
        behave like futureWithResult { test ⇒
          val actor = actorOf[TestActor]
          actor.start()
          val future = actor ? "Hello"
          future.await
          test(future, "World")
          actor.stop()
        }
      }
      "throws an exception" must {
        behave like futureWithException[RuntimeException] { test ⇒
          val actor = actorOf[TestActor]
          actor.start()
          val future = actor ? "Failure"
          future.await
          test(future, "Expected exception; to test fault-tolerance")
          actor.stop()
        }
      }
    }

    "using flatMap with an Actor" that {
      "will return a result" must {
        behave like futureWithResult { test ⇒
          val actor1 = actorOf[TestActor].start()
          val actor2 = actorOf(new Actor { def receive = { case s: String ⇒ self reply s.toUpperCase } }).start()
          val future = actor1 ? "Hello" flatMap { _ match { case s: String ⇒ actor2 ? s } }
          future.await
          test(future, "WORLD")
          actor1.stop()
          actor2.stop()
        }
      }
      "will throw an exception" must {
        behave like futureWithException[ArithmeticException] { test ⇒
          val actor1 = actorOf[TestActor].start()
          val actor2 = actorOf(new Actor { def receive = { case s: String ⇒ self reply (s.length / 0) } }).start()
          val future = actor1 ? "Hello" flatMap { _ match { case s: String ⇒ actor2 ? s } }
          future.await
          test(future, "/ by zero")
          actor1.stop()
          actor2.stop()
        }
      }
    }

    "being tested" must {

      "compose with for-comprehensions" in {
        val actor = actorOf(new Actor {
          def receive = {
            case s: String ⇒ self reply s.length
            case i: Int    ⇒ self reply (i * 2).toString
          }
        }).start()

        val future0 = actor ? "Hello"

        val future1 = for {
          a: Int ← future0.mapTo[Int] // returns 5
          b: String ← (actor ? a).mapTo[String] // returns "10"
          c: String ← (actor ? 7).mapTo[String] // returns "14"
        } yield b + "-" + c

        val future2 = for {
          a: Int ← future0.mapTo[Int]
          b: Int ← (actor ? a).mapTo[Int]
          c: String ← (actor ? 7).mapTo[String]
        } yield b + "-" + c

        future1.get must be("10-14")
        intercept[ClassCastException] { future2.get }
        actor.stop()
      }

      "support pattern matching within a for-comprehension" in {
        case class Req[T](req: T)
        case class Res[T](res: T)
        val actor = actorOf(new Actor {
          def receive = {
            case Req(s: String) ⇒ self reply Res(s.length)
            case Req(i: Int)    ⇒ self reply Res((i * 2).toString)
          }
        }).start()

        val future1 = for {
          Res(a: Int) ← actor ? Req("Hello")
          Res(b: String) ← actor ? Req(a)
          Res(c: String) ← actor ? Req(7)
        } yield b + "-" + c

        val future2 = for {
          Res(a: Int) ← actor ? Req("Hello")
          Res(b: Int) ← actor ? Req(a)
          Res(c: Int) ← actor ? Req(7)
        } yield b + "-" + c

        future1.get must be("10-14")
        intercept[MatchError] { future2.get }
        actor.stop()
      }

      "recover from exceptions" in {
        val future1 = Future(5)
        val future2 = future1 map (_ / 0)
        val future3 = future2 map (_.toString)

        val future4 = future1 recover {
          case e: ArithmeticException ⇒ 0
        } map (_.toString)

        val future5 = future2 recover {
          case e: ArithmeticException ⇒ 0
        } map (_.toString)

        val future6 = future2 recover {
          case e: MatchError ⇒ 0
        } map (_.toString)

        val future7 = future3 recover { case e: ArithmeticException ⇒ "You got ERROR" }

        val actor = actorOf[TestActor].start()

        val future8 = actor ? "Failure"
        val future9 = actor ? "Failure" recover {
          case e: RuntimeException ⇒ "FAIL!"
        }
        val future10 = actor ? "Hello" recover {
          case e: RuntimeException ⇒ "FAIL!"
        }
        val future11 = actor ? "Failure" recover { case _ ⇒ "Oops!" }

        future1.get must be(5)
        intercept[ArithmeticException] { future2.get }
        intercept[ArithmeticException] { future3.get }
        future4.get must be("5")
        future5.get must be("0")
        intercept[ArithmeticException] { future6.get }
        future7.get must be("You got ERROR")
        intercept[RuntimeException] { future8.get }
        future9.get must be("FAIL!")
        future10.get must be("World")
        future11.get must be("Oops!")

        actor.stop()
      }

      "fold" in {
        val actors = (1 to 10).toList map { _ ⇒
          actorOf(new Actor {
            def receive = { case (add: Int, wait: Int) ⇒ Thread.sleep(wait); self reply_? add }
          }).start()
        }
        val timeout = 10000
        def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 200), timeout).mapTo[Int] }
        Futures.fold(0, timeout)(futures)(_ + _).get must be(45)
      }

      "fold by composing" in {
        val actors = (1 to 10).toList map { _ ⇒
          actorOf(new Actor {
            def receive = { case (add: Int, wait: Int) ⇒ Thread.sleep(wait); self reply_? add }
          }).start()
        }
        def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 200), 10000).mapTo[Int] }
        futures.foldLeft(Future(0))((fr, fa) ⇒ for (r ← fr; a ← fa) yield (r + a)).get must be(45)
      }

      "fold with an exception" in {
        val actors = (1 to 10).toList map { _ ⇒
          actorOf(new Actor {
            def receive = {
              case (add: Int, wait: Int) ⇒
                Thread.sleep(wait)
                if (add == 6) throw new IllegalArgumentException("shouldFoldResultsWithException: expected")
                self reply_? add
            }
          }).start()
        }
        val timeout = 10000
        def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 100), timeout).mapTo[Int] }
        Futures.fold(0, timeout)(futures)(_ + _).await.exception.get.getMessage must be("shouldFoldResultsWithException: expected")
      }

      "return zero value if folding empty list" in {
        Futures.fold(0)(List[Future[Int]]())(_ + _).get must be(0)
      }

      "shouldReduceResults" in {
        val actors = (1 to 10).toList map { _ ⇒
          actorOf(new Actor {
            def receive = { case (add: Int, wait: Int) ⇒ Thread.sleep(wait); self reply_? add }
          }).start()
        }
        val timeout = 10000
        def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 200), timeout).mapTo[Int] }
        assert(Futures.reduce(futures, timeout)(_ + _).get === 45)
      }

      "shouldReduceResultsWithException" in {
        val actors = (1 to 10).toList map { _ ⇒
          actorOf(new Actor {
            def receive = {
              case (add: Int, wait: Int) ⇒
                Thread.sleep(wait)
                if (add == 6) throw new IllegalArgumentException("shouldFoldResultsWithException: expected")
                self reply_? add
            }
          }).start()
        }
        val timeout = 10000
        def futures = actors.zipWithIndex map { case (actor: ActorRef, idx: Int) ⇒ actor.?((idx, idx * 100), timeout).mapTo[Int] }
        assert(Futures.reduce(futures, timeout)(_ + _).await.exception.get.getMessage === "shouldFoldResultsWithException: expected")
      }

      "shouldReduceThrowIAEOnEmptyInput" in {
        intercept[UnsupportedOperationException] { Futures.reduce(List[Future[Int]]())(_ + _).get }
      }

      "receiveShouldExecuteOnComplete" in {
        val latch = new StandardLatch
        val actor = actorOf[TestActor].start()
        actor ? "Hello" onResult { case "World" ⇒ latch.open }
        assert(latch.tryAwait(5, TimeUnit.SECONDS))
        actor.stop()
      }

      "shouldTraverseFutures" in {
        val oddActor = actorOf(new Actor {
          var counter = 1
          def receive = {
            case 'GetNext ⇒
              self reply counter
              counter += 2
          }
        }).start()

        val oddFutures = List.fill(100)(oddActor ? 'GetNext mapTo manifest[Int])
        assert(Future.sequence(oddFutures).get.sum === 10000)
        oddActor.stop()

        val list = (1 to 100).toList
        assert(Future.traverse(list)(x ⇒ Future(x * 2 - 1)).get.sum === 10000)
      }

      "shouldHandleThrowables" in {
        class ThrowableTest(m: String) extends Throwable(m)

        val f1 = Future { throw new ThrowableTest("test") }
        f1.await
        intercept[ThrowableTest] { f1.resultOrException }

        val latch = new StandardLatch
        val f2 = Future { latch.tryAwait(5, TimeUnit.SECONDS); "success" }
        f2 foreach (_ ⇒ throw new ThrowableTest("dispatcher foreach"))
        f2 onResult { case _ ⇒ throw new ThrowableTest("dispatcher receive") }
        val f3 = f2 map (s ⇒ s.toUpperCase)
        latch.open
        f2.await
        assert(f2.resultOrException === Some("success"))
        f2 foreach (_ ⇒ throw new ThrowableTest("current thread foreach"))
        f2 onResult { case _ ⇒ throw new ThrowableTest("current thread receive") }
        f3.await
        assert(f3.resultOrException === Some("SUCCESS"))

        // make sure all futures are completed in dispatcher
        assert(Dispatchers.defaultGlobalDispatcher.pendingFutures === 0)
      }

      "shouldBlockUntilResult" in {
        val latch = new StandardLatch

        val f = Future({ latch.await; 5 })
        val f2 = Future({ f.get + 5 })

        assert(f2.resultOrException === None)
        latch.open
        assert(f2.get === 10)

        val f3 = Future({ Thread.sleep(100); 5 }, 10)
        intercept[FutureTimeoutException] {
          f3.get
        }
      }

      "futureComposingWithContinuations" in {
        import Future.flow

        val actor = actorOf[TestActor].start

        val x = Future("Hello")
        val y = x flatMap (actor ? _) mapTo manifest[String]

        val r = flow(x() + " " + y() + "!")

        assert(r.get === "Hello World!")

        actor.stop
      }

      "futureComposingWithContinuationsFailureDivideZero" in {
        import Future.flow

        val x = Future("Hello")
        val y = x map (_.length)

        val r = flow(x() + " " + y.map(_ / 0).map(_.toString)(), 100)

        intercept[java.lang.ArithmeticException](r.get)
      }

      "futureComposingWithContinuationsFailureCastInt" in {
        import Future.flow

        val actor = actorOf[TestActor].start

        val x = Future(3)
        val y = (actor ? "Hello").mapTo[Int]

        val r = flow(x() + y(), 100)

        intercept[ClassCastException](r.get)
      }

      "futureComposingWithContinuationsFailureCastNothing" in {
        import Future.flow

        val actor = actorOf[TestActor].start

        val x = Future("Hello")
        val y = actor ? "Hello" mapTo manifest[Nothing]

        val r = flow(x() + y())

        intercept[ClassCastException](r.get)
      }

      "futureCompletingWithContinuations" in {
        import Future.flow

        val x, y, z = Promise[Int]()
        val ly, lz = new StandardLatch

        val result = flow {
          y completeWith x
          ly.open // not within continuation

          z << x
          lz.open // within continuation, will wait for 'z' to complete
          z() + y()
        }

        assert(ly.tryAwaitUninterruptible(100, TimeUnit.MILLISECONDS))
        assert(!lz.tryAwaitUninterruptible(100, TimeUnit.MILLISECONDS))

        flow { x << 5 }

        assert(y.get === 5)
        assert(z.get === 5)
        assert(lz.isOpen)
        assert(result.get === 10)

        val a, b, c = Promise[Int]()

        val result2 = flow {
          val n = (a << c).result.get + 10
          b << (c() - 2)
          a() + n * b()
        }

        c completeWith Future(5)

        assert(a.get === 5)
        assert(b.get === 3)
        assert(result2.get === 50)
        Thread.sleep(100)

        // make sure all futures are completed in dispatcher
        assert(Dispatchers.defaultGlobalDispatcher.pendingFutures === 0)
      }

      "shouldNotAddOrRunCallbacksAfterFailureToBeCompletedBeforeExpiry" in {
        val latch = new StandardLatch
        val f = Promise[Int](0)
        Thread.sleep(25)
        f.onComplete(_ ⇒ latch.open) //Shouldn't throw any exception here

        assert(f.isExpired) //Should be expired

        f.complete(Right(1)) //Shouldn't complete the Future since it is expired

        assert(f.value.isEmpty) //Shouldn't be completed
        assert(!latch.isOpen) //Shouldn't run the listener
      }

      "futureDataFlowShouldEmulateBlocking1" in {
        import Future.flow

        val one, two = Promise[Int](1000 * 60)
        val simpleResult = flow {
          one() + two()
        }

        assert(List(one, two, simpleResult).forall(_.isCompleted == false))

        flow { one << 1 }

        assert(one.isCompleted)
        assert(List(two, simpleResult).forall(_.isCompleted == false))

        flow { two << 9 }

        assert(List(one, two).forall(_.isCompleted == true))
        assert(simpleResult.get === 10)

      }

      "futureDataFlowShouldEmulateBlocking2" in {
        import Future.flow
        val x1, x2, y1, y2 = Promise[Int](1000 * 60)
        val lx, ly, lz = new StandardLatch
        val result = flow {
          lx.open()
          x1 << y1
          ly.open()
          x2 << y2
          lz.open()
          x1() + x2()
        }
        assert(lx.isOpen)
        assert(!ly.isOpen)
        assert(!lz.isOpen)
        assert(List(x1, x2, y1, y2).forall(_.isCompleted == false))

        flow { y1 << 1 } // When this is set, it should cascade down the line

        assert(ly.tryAwaitUninterruptible(2000, TimeUnit.MILLISECONDS))
        assert(x1.get === 1)
        assert(!lz.isOpen)

        flow { y2 << 9 } // When this is set, it should cascade down the line

        assert(lz.tryAwaitUninterruptible(2000, TimeUnit.MILLISECONDS))
        assert(x2.get === 9)

        assert(List(x1, x2, y1, y2).forall(_.isCompleted == true))

        assert(result.get === 10)
      }

      "dataFlowAPIshouldbeSlick" in {
        import Future.flow

        val i1, i2, s1, s2 = new StandardLatch

        val callService1 = Future { i1.open; s1.awaitUninterruptible; 1 }
        val callService2 = Future { i2.open; s2.awaitUninterruptible; 9 }

        val result = flow { callService1() + callService2() }

        assert(!s1.isOpen)
        assert(!s2.isOpen)
        assert(!result.isCompleted)
        assert(i1.tryAwaitUninterruptible(2000, TimeUnit.MILLISECONDS))
        assert(i2.tryAwaitUninterruptible(2000, TimeUnit.MILLISECONDS))
        s1.open
        s2.open
        assert(result.get === 10)
      }

      "futureCompletingWithContinuationsFailure" in {
        import Future.flow

        val x, y, z = Promise[Int]()
        val ly, lz = new StandardLatch

        val result = flow {
          y << x
          ly.open
          val oops = 1 / 0
          z << x
          lz.open
          z() + y() + oops
        }

        assert(!ly.tryAwaitUninterruptible(100, TimeUnit.MILLISECONDS))
        assert(!lz.tryAwaitUninterruptible(100, TimeUnit.MILLISECONDS))

        flow { x << 5 }

        assert(y.get === 5)
        intercept[java.lang.ArithmeticException](result.get)
        assert(z.value === None)
        assert(!lz.isOpen)
      }

      "futureContinuationsShouldNotBlock" in {
        import Future.flow

        val latch = new StandardLatch
        val future = Future {
          latch.await
          "Hello"
        }

        val result = flow {
          Some(future()).filter(_ == "Hello")
        }

        assert(!result.isCompleted)

        latch.open

        assert(result.get === Some("Hello"))
      }

      "futureFlowShouldBeTypeSafe" in {
        import Future.flow

        def checkType[A: Manifest, B](in: Future[A], refmanifest: Manifest[B]): Boolean = manifest[A] == refmanifest

        val rString = flow {
          val x = Future(5)
          x().toString
        }

        val rInt = flow {
          val x = rString.apply
          val y = Future(5)
          x.length + y()
        }

        assert(checkType(rString, manifest[String]))
        assert(checkType(rInt, manifest[Int]))
        assert(!checkType(rInt, manifest[String]))
        assert(!checkType(rInt, manifest[Nothing]))
        assert(!checkType(rInt, manifest[Any]))

        rString.await
        rInt.await
      }

      "futureFlowSimpleAssign" in {
        import Future.flow

        val x, y, z = Promise[Int]()

        flow {
          z << x() + y()
        }
        flow { x << 40 }
        flow { y << 2 }

        assert(z.get === 42)
      }

      "futureFlowLoops" in {
        import Future.flow
        import akka.util.cps._

        val count = 10000

        val promises = List.fill(count)(Promise[Int]())

        flow {
          var i = 0
          val iter = promises.iterator
          whileC(iter.hasNext) {
            iter.next << i
            i += 1
          }
        }

        var i = 0
        promises foreach { p ⇒
          assert(p.get === i)
          i += 1
        }

        assert(i === count)

      }

      "ticket812FutureDispatchCleanup" in {
        val dispatcher = implicitly[MessageDispatcher]
        assert(dispatcher.pendingFutures === 0)
        val future = Future({ Thread.sleep(100); "Done" }, 10)
        intercept[FutureTimeoutException] { future.await }
        assert(dispatcher.pendingFutures === 1)
        Thread.sleep(100)
        assert(dispatcher.pendingFutures === 0)
      }
    }
  }

  def emptyFuture(f: (Future[Any] ⇒ Unit) ⇒ Unit) {
    "not be completed" in { f(_ must not be ('completed)) }
    "not be expired" in { f(_ must not be ('expired)) }
    "not contain a value" in { f(_.value must be(None)) }
    "not contain a result" in { f(_.result must be(None)) }
    "not contain an exception" in { f(_.exception must be(None)) }
  }

  def futureWithResult(f: ((Future[Any], Any) ⇒ Unit) ⇒ Unit) {
    "be completed" in { f((future, _) ⇒ future must be('completed)) }
    "not be expired" in { f((future, _) ⇒ future must not be ('expired)) }
    "contain a value" in { f((future, result) ⇒ future.value must be(Some(Right(result)))) }
    "contain a result" in { f((future, result) ⇒ future.result must be(Some(result))) }
    "not contain an exception" in { f((future, _) ⇒ future.exception must be(None)) }
    "return result with 'get'" in { f((future, result) ⇒ future.get must be(result)) }
    "return result with 'resultOrException'" in { f((future, result) ⇒ future.resultOrException must be(Some(result))) }
    "not timeout" in { f((future, _) ⇒ future.await) }
    "filter result" in {
      f { (future, result) ⇒
        (future filter (_ ⇒ true)).get must be(result)
        (evaluating { (future filter (_ ⇒ false)).get } must produce[MatchError]).getMessage must startWith(result.toString)
      }
    }
    "transform result with map" in { f((future, result) ⇒ (future map (_.toString.length)).get must be(result.toString.length)) }
    "compose result with flatMap" is pending
    "perform action with foreach" is pending
    "match result with collect" is pending
    "not recover from exception" is pending
    "perform action on result" is pending
    "not perform action on exception" is pending
    "cast using mapTo" is pending
  }

  def futureWithException[E <: Throwable: Manifest](f: ((Future[Any], String) ⇒ Unit) ⇒ Unit) {
    "be completed" in { f((future, _) ⇒ future must be('completed)) }
    "not be expired" in { f((future, _) ⇒ future must not be ('expired)) }
    "contain a value" in { f((future, _) ⇒ future.value must be('defined)) }
    "not contain a result" in { f((future, _) ⇒ future.result must be(None)) }
    "contain an exception" in { f((future, message) ⇒ future.exception.get.getMessage must be(message)) }
    "throw exception with 'get'" in { f((future, message) ⇒ (evaluating { future.get } must produce[E]).getMessage must be(message)) }
    "throw exception with 'resultOrException'" in { f((future, message) ⇒ (evaluating { future.resultOrException } must produce[E]).getMessage must be(message)) }
    "not timeout" in { f((future, _) ⇒ future.await) }
    "retain exception with filter" in {
      f { (future, message) ⇒
        (evaluating { (future filter (_ ⇒ true)).get } must produce[E]).getMessage must be(message)
        (evaluating { (future filter (_ ⇒ false)).get } must produce[E]).getMessage must be(message)
      }
    }
    "retain exception with map" in { f((future, message) ⇒ (evaluating { (future map (_.toString.length)).get } must produce[E]).getMessage must be(message)) }
    "retain exception with flatMap" is pending
    "not perform action with foreach" is pending
    "retain exception with collect" is pending
    "recover from exception" is pending
    "not perform action on result" is pending
    "perform action on exception" is pending
    "always cast successfully using mapTo" is pending
  }

  def expiredFuture(f: (Future[Any] ⇒ Unit) ⇒ Unit) {
    "not be completed" in { f(_ must not be ('completed)) }
    "be expired" in { f(_ must be('expired)) }
  }

  sealed trait IntAction

  sealed trait FutureAction {
    def /:(that: Either[Throwable, Int]): Either[Throwable, Int]
    def /:(that: Future[Int]): Future[Int]
  }

  case class MapAction(f: Int ⇒ Int) extends FutureAction {
    def /:(that: Either[Throwable, Int]): Either[Throwable, Int] = that match {
      case Left(e)  ⇒ that
      case Right(r) ⇒ try { Right(f(r)) } catch { case e ⇒ Left(e) }
    }
    def /:(that: Future[Int]): Future[Int] = that map f
  }

  case class FlatMapAction(f: Int ⇒ Future[Int]) extends FutureAction {
    def /:(that: Either[Throwable, Int]): Either[Throwable, Int] = that match {
      case Left(e)  ⇒ that
      case Right(r) ⇒ try { f(r).await.value.get } catch { case e ⇒ Left(e) }
    }
    def /:(that: Future[Int]): Future[Int] = that flatMap f
  }

  implicit def arbFuture: Arbitrary[Future[Int]] = Arbitrary(for (n ← arbitrary[Int]) yield Future(n))

  implicit def arbFutureAction: Arbitrary[FutureAction] = Arbitrary {

    val genMapAction = for {
      n ← arbitrary[Int]
      a ← oneOf('a, 's, 'm, 'd)
    } yield MapAction(a match {
      case 'a ⇒ _ + n
      case 's ⇒ _ - n
      case 'm ⇒ _ * n
      case 'd ⇒ _ / n
    })

    val genFlatMapAction = for {
      n ← arbitrary[Int]
      a ← oneOf('a, 's, 'm, 'd)
    } yield FlatMapAction(a match {
      case 'a ⇒ (r: Int) ⇒ Future(r + n)
      case 's ⇒ (r: Int) ⇒ Future(r - n)
      case 'm ⇒ (r: Int) ⇒ Future(r * n)
      case 'd ⇒ (r: Int) ⇒ Future(r / n)
    })

    oneOf(genMapAction, genFlatMapAction)

  }

}
