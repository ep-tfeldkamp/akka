/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.Address
import akka.testkit.{ LongRunningTest, AkkaSpec }

class AccrualFailureDetectorSpec extends AkkaSpec("""
  akka.loglevel = "INFO"
""") {

  "An AccrualFailureDetector" must {
    val conn = Address("akka", "", "localhost", 2552)
    val conn2 = Address("akka", "", "localhost", 2553)

    def fakeTimeGenerator(timeIntervals: List[Long]): () ⇒ Long = {
      var times = timeIntervals.tail.foldLeft(List[Long](timeIntervals.head))((acc, c) ⇒ acc ::: List[Long](acc.last + c))
      def timeGenerator(): Long = {
        val currentTime = times.head
        times = times.tail
        currentTime
      }
      timeGenerator
    }

    "return phi value of 0.0D on startup for each address" in {
      val fd = new AccrualFailureDetector(system, conn)
      fd.phi(conn) must be(0.0D)
      fd.phi(conn2) must be(0.0D)
    }

    "mark node as available after a series of successful heartbeats" in {
      var timeInterval = List[Long](0, 1000, 100, 100)
      val ft = fakeTimeGenerator(timeInterval)

      val fd = new AccrualFailureDetector(system, conn, timeMachine = ft)

      fd.heartbeat(conn)

      fd.heartbeat(conn)

      fd.heartbeat(conn)

      fd.isAvailable(conn) must be(true)
    }

    "mark node as dead after explicit removal of connection" in {
      var timeInterval = List[Long](0, 1000, 100, 100, 100)
      val ft = fakeTimeGenerator(timeInterval)

      val fd = new AccrualFailureDetector(system, conn, timeMachine = ft)

      fd.heartbeat(conn)

      fd.heartbeat(conn)

      fd.heartbeat(conn)

      fd.isAvailable(conn) must be(true)

      fd.remove(conn)

      fd.isAvailable(conn) must be(false)
    }

    "mark node as available after explicit removal of connection and receiving heartbeat again" in {
      var timeInterval = List[Long](0, 1000, 100, 1100, 1100, 1100, 1100, 1100, 100)
      val ft = fakeTimeGenerator(timeInterval)

      val fd = new AccrualFailureDetector(system, conn, timeMachine = ft)

      fd.heartbeat(conn) //0

      fd.heartbeat(conn) //1000

      fd.heartbeat(conn) //1100

      fd.isAvailable(conn) must be(true) //2200

      fd.remove(conn)

      fd.isAvailable(conn) must be(false) //3300

      // it receives heartbeat from an explicitly removed node
      fd.heartbeat(conn) //4400

      fd.heartbeat(conn) //5500

      fd.heartbeat(conn) //6600

      fd.isAvailable(conn) must be(true) //6700
    }

    "mark node as dead if heartbeat are missed" in {
      var timeInterval = List[Long](0, 1000, 100, 100, 5000)
      val ft = fakeTimeGenerator(timeInterval)

      val fd = new AccrualFailureDetector(system, conn, threshold = 3, timeMachine = ft)

      fd.heartbeat(conn) //0

      fd.heartbeat(conn) //1000

      fd.heartbeat(conn) //1100

      fd.isAvailable(conn) must be(true) //1200

      fd.isAvailable(conn) must be(false) //6200
    }

    "mark node as available if it starts heartbeat again after being marked dead due to detection of failure" in {
      var timeInterval = List[Long](0, 1000, 100, 1100, 5000, 100, 1000, 100, 100)
      val ft = fakeTimeGenerator(timeInterval)

      val fd = new AccrualFailureDetector(system, conn, threshold = 3, timeMachine = ft)

      fd.heartbeat(conn) //0

      fd.heartbeat(conn) //1000

      fd.heartbeat(conn) //1100

      fd.isAvailable(conn) must be(true) //1200

      fd.isAvailable(conn) must be(false) //6200

      fd.heartbeat(conn) //6300

      fd.heartbeat(conn) //7300

      fd.heartbeat(conn) //7400

      fd.isAvailable(conn) must be(true) //7500
    }
  }
}
