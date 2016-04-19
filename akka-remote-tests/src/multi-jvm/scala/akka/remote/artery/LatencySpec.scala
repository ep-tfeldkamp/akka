/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.net.InetAddress
import java.util.concurrent.Executors
import scala.collection.AbstractIterator
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor._
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit._
import com.typesafe.config.ConfigFactory
import io.aeron.Aeron
import io.aeron.driver.MediaDriver
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import org.HdrHistogram.Histogram
import akka.stream.ThrottleMode
import java.io.StringWriter
import java.io.PrintStream
import java.io.OutputStreamWriter
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream

object LatencySpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  val barrierTimeout = 5.minutes

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString(s"""
       # for serious measurements you should increase the totalMessagesFactor (10) and repeatCount (3)
       akka.test.LatencySpec.totalMessagesFactor = 1.0
       akka.test.LatencySpec.repeatCount = 1
       akka {
         loglevel = ERROR
         testconductor.barrier-timeout = ${barrierTimeout.toSeconds}s
         actor {
           provider = "akka.remote.RemoteActorRefProvider"
           serialize-creators = false
           serialize-messages = false
         }
         remote.artery {
           enabled = on
         }
       }
       """)))

  def aeronPort(roleName: RoleName): Int =
    roleName match {
      case `first`  ⇒ 20501 // TODO yeah, we should have support for dynamic port assignment
      case `second` ⇒ 20502
    }

  nodeConfig(first) {
    ConfigFactory.parseString(s"""
      akka.remote.artery.port = ${aeronPort(first)}
      """)
  }

  nodeConfig(second) {
    ConfigFactory.parseString(s"""
      akka.remote.artery.port = ${aeronPort(second)}
      """)
  }

  final case object Reset

  def echoProps(): Props =
    Props(new Echo)

  class Echo extends Actor {
    // FIXME to avoid using new RemoteActorRef each time
    var cachedSender: ActorRef = null

    def receive = {
      case Reset ⇒
        cachedSender = null
        sender() ! Reset
      case msg ⇒
        if (cachedSender == null) cachedSender = sender()
        cachedSender ! msg
    }
  }

  def receiverProps(reporter: RateReporter, settings: TestSettings, totalMessages: Int,
                    sendTimes: AtomicLongArray, histogram: Histogram): Props =
    Props(new Receiver(reporter, settings, totalMessages, sendTimes, histogram))

  class Receiver(reporter: RateReporter, settings: TestSettings, totalMessages: Int,
                 sendTimes: AtomicLongArray, histogram: Histogram) extends Actor {
    import settings._

    var count = 0

    def receive = {
      case bytes: Array[Byte] ⇒
        if (bytes.length != payloadSize) throw new IllegalArgumentException("Invalid message")
        reporter.onMessage(1, payloadSize)
        count += 1
        val d = System.nanoTime() - sendTimes.get(count - 1)
        histogram.recordValue(d)
        if (count == totalMessages) {
          printTotal(testName, bytes.length, histogram)
          context.stop(self)
        }
    }

    def printTotal(testName: String, payloadSize: Long, histogram: Histogram): Unit = {
      import scala.collection.JavaConverters._
      val percentiles = histogram.percentiles(5)
      def percentile(p: Double): Double =
        percentiles.iterator().asScala.collectFirst {
          case value if (p - 0.5) < value.getPercentileLevelIteratedTo &&
            value.getPercentileLevelIteratedTo < (p + 0.5) ⇒ value.getValueIteratedTo / 1000.0
        }.getOrElse(Double.NaN)

      println(s"=== Latency $testName: RTT " +
        f"50%%ile: ${percentile(50.0)}%.0f µs, " +
        f"90%%ile: ${percentile(90.0)}%.0f µs, " +
        f"99%%ile: ${percentile(99.0)}%.0f µs, ")
      println("Histogram of RTT latencies in microseconds.")
      histogram.outputPercentileDistribution(System.out, 1000.0)
    }
  }

  final case class TestSettings(
    testName: String,
    messageRate: Int, // msg/s
    payloadSize: Int,
    repeat: Int)

}

class LatencySpecMultiJvmNode1 extends LatencySpec
class LatencySpecMultiJvmNode2 extends LatencySpec

abstract class LatencySpec
  extends MultiNodeSpec(LatencySpec)
  with STMultiNodeSpec with ImplicitSender {

  import LatencySpec._

  val totalMessagesFactor = system.settings.config.getDouble("akka.test.LatencySpec.totalMessagesFactor")
  val repeatCount = system.settings.config.getInt("akka.test.LatencySpec.repeatCount")

  val aeron = {
    val ctx = new Aeron.Context
    val driver = MediaDriver.launchEmbedded()
    ctx.aeronDirectoryName(driver.aeronDirectoryName)
    Aeron.connect(ctx)
  }

  lazy implicit val mat = ActorMaterializer()(system)
  import system.dispatcher

  override def initialParticipants = roles.size

  def channel(roleName: RoleName) = {
    val a = node(roleName).address
    s"aeron:udp?endpoint=${a.host.get}:${aeronPort(roleName)}"
  }

  lazy val reporterExecutor = Executors.newFixedThreadPool(1)
  def reporter(name: String): RateReporter = {
    val r = new RateReporter(SECONDS.toNanos(1), new RateReporter.Reporter {
      override def onReport(messagesPerSec: Double, bytesPerSec: Double, totalMessages: Long, totalBytes: Long): Unit = {
        println(name + ": %.03g msgs/sec, %.03g bytes/sec, totals %d messages %d MB".format(
          messagesPerSec, bytesPerSec, totalMessages, totalBytes / (1024 * 1024)))
      }
    })
    reporterExecutor.execute(r)
    r
  }

  override def afterAll(): Unit = {
    reporterExecutor.shutdown()
    super.afterAll()
  }

  def identifyEcho(name: String = "echo", r: RoleName = second): ActorRef = {
    system.actorSelection(node(r) / "user" / name) ! Identify(None)
    expectMsgType[ActorIdentity].ref.get
  }

  val scenarios = List(
    TestSettings(
      testName = "rate-100-size-100",
      messageRate = 100,
      payloadSize = 100,
      repeat = repeatCount),
    TestSettings(
      testName = "rate-1000-size-100",
      messageRate = 1000,
      payloadSize = 100,
      repeat = repeatCount),
    TestSettings(
      testName = "rate-10000-size-100",
      messageRate = 10000,
      payloadSize = 100,
      repeat = repeatCount),
    TestSettings(
      testName = "rate-1000-size-1k",
      messageRate = 1000,
      payloadSize = 1000,
      repeat = repeatCount))

  def test(testSettings: TestSettings): Unit = {
    import testSettings._

    runOn(first) {
      val payload = ("0" * payloadSize).getBytes("utf-8")
      // by default run for 2 seconds, but can be adjusted with the totalMessagesFactor
      val totalMessages = (2 * messageRate * totalMessagesFactor).toInt
      val sendTimes = new AtomicLongArray(totalMessages)
      val histogram = new Histogram(SECONDS.toNanos(10), 3)
      val rep = reporter(testName)

      val echo = identifyEcho()

      for (n ← 1 to repeat) {
        echo ! Reset
        expectMsg(Reset)
        histogram.reset()
        val receiver = system.actorOf(receiverProps(rep, testSettings, totalMessages, sendTimes, histogram))

        Source(1 to totalMessages)
          .throttle(messageRate, 1.second, math.max(messageRate / 10, 1), ThrottleMode.Shaping)
          .runForeach { n ⇒
            sendTimes.set(n - 1, System.nanoTime())
            echo.tell(payload, receiver)
          }

        watch(receiver)
        expectTerminated(receiver, ((totalMessages / messageRate) + 10).seconds)
      }

      rep.halt()
    }

    enterBarrier("after-" + testName)
  }

  "Latency of Artery" must {

    "start echo" in {
      runOn(second) {
        // just echo back
        system.actorOf(echoProps, "echo")
      }
      enterBarrier("echo-started")
    }

    for (s ← scenarios) {
      s"be low for ${s.testName}, at ${s.messageRate} msg/s, payloadSize = ${s.payloadSize}" in test(s)
    }

    // TODO add more tests

  }
}
