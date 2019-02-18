/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import java.io.{FileInputStream, InputStreamReader}
import java.util.Properties

import sbt.Keys._
import sbt._

import scala.collection.breakOut

object AkkaBuild {

  val enableMiMa = true

  val parallelExecutionByDefault = false // TODO: enable this once we're sure it does not break things

  lazy val buildSettings = Dependencies.Versions ++ Seq(
    organization := "com.typesafe.akka",
    // use the same value as in the build scope, so it can be overriden by stampVersion
    //version := (version in ThisBuild).value
        version := "2.5.12-dg-1.0.0"
  )

  lazy val rootSettings = Release.settings ++
    UnidocRoot.akkaSettings ++
    Formatting.formatSettings ++
    Protobuf.settings ++ Seq(
      parallelExecution in GlobalScope := System.getProperty("akka.parallelExecution", parallelExecutionByDefault.toString).toBoolean,
      version in ThisBuild := "2.5.12"
    )

  lazy val mayChangeSettings = Seq(
    description := """|This module of Akka is marked as
                      |'may change', which means that it is in early
                      |access mode, which also means that it is not covered
                      |by commercial support. An module marked 'may change' doesn't
                      |have to obey the rule of staying binary compatible
                      |between minor releases. Breaking API changes may be
                      |introduced in minor releases without notice as we
                      |refine and simplify based on your feedback. Additionally
                      |such a module may be dropped in major releases
                      |without prior deprecation.
                      |""".stripMargin)


  private def allWarnings: Boolean = System.getProperty("akka.allwarnings", "false").toBoolean

  lazy val defaultSettings = TestExtras.Filter.settings ++
    Protobuf.settings ++ Seq[Setting[_]](
      // compile options
      scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.8", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
      scalacOptions in Compile ++= (if (allWarnings) Seq("-deprecation") else Nil),
      scalacOptions in Test := (scalacOptions in Test).value.filterNot(opt ⇒
        opt == "-Xlog-reflective-calls" || opt.contains("genjavadoc")),
      // -XDignore.symbol.file suppresses sun.misc.Unsafe warnings
      javacOptions in compile ++= Seq("-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-XDignore.symbol.file"),
      javacOptions in compile ++= (if (allWarnings) Seq("-Xlint:deprecation") else Nil),
      javacOptions in doc ++= Seq(),

      crossVersion := CrossVersion.binary,

      ivyLoggingLevel in ThisBuild := UpdateLogging.Quiet,

      licenses := Seq(("Apache License, Version 2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),
      homepage := Some(url("http://akka.io/")),

      apiURL := Some(url(s"http://doc.akka.io/api/akka/${version.value}")),

      initialCommands :=
        """|import language.postfixOps
         |import akka.actor._
         |import ActorDSL._
         |import scala.concurrent._
         |import com.typesafe.config.ConfigFactory
         |import scala.concurrent.duration._
         |import akka.util.Timeout
         |var config = ConfigFactory.parseString("akka.stdout-loglevel=INFO,akka.loglevel=DEBUG,pinned{type=PinnedDispatcher,executor=thread-pool-executor,throughput=1000}")
         |var remoteConfig = ConfigFactory.parseString("akka.remote.netty{port=0,use-dispatcher-for-io=akka.actor.default-dispatcher,execution-pool-size=0},akka.actor.provider=remote").withFallback(config)
         |var system: ActorSystem = null
         |implicit def _system = system
         |def startSystem(remoting: Boolean = false) { system = ActorSystem("repl", if(remoting) remoteConfig else config); println("don’t forget to system.terminate()!") }
         |implicit def ec = system.dispatcher
         |implicit val timeout = Timeout(5 seconds)
         |""".stripMargin,

      /**
       * Test settings
       */
      fork in Test := true,

      // default JVM config for tests
      javaOptions in Test ++= {
        val defaults = Seq(
          // ## core memory settings
          "-XX:+UseG1GC",
          // most tests actually don't really use _that_ much memory (>1g usually)
          // twice used (and then some) keeps G1GC happy - very few or to no full gcs
          "-Xms3g", "-Xmx3g",
          // increase stack size (todo why?)
          "-Xss2m",

          // ## extra memory/gc tuning
          // this breaks jstat, but could avoid costly syncs to disc see http://www.evanjones.ca/jvm-mmap-pause.html
          "-XX:+PerfDisableSharedMem",
          // tell G1GC that we would be really happy if all GC pauses could be kept below this as higher would
          // likely start causing test failures in timing tests
          "-XX:MaxGCPauseMillis=300",
          // nio direct memory limit for artery/aeron (probably)
          "-XX:MaxDirectMemorySize=256m",

          // faster random source
          "-Djava.security.egd=file:/dev/./urandom")

        if (sys.props.contains("akka.ci-server"))
          defaults ++ Seq("-XX:+PrintGCTimeStamps", "-XX:+PrintGCDetails")
        else
          defaults
      },

      // all system properties passed to sbt prefixed with "akka." will be passed on to the forked jvms as is
      javaOptions in Test := {
        val base = (javaOptions in Test).value
        val akkaSysProps: Seq[String] =
          sys.props.filter(_._1.startsWith("akka"))
            .map { case (key, value) ⇒ s"-D$key=$value" }(breakOut)

        base ++ akkaSysProps
      },

      // with forked tests the working directory is set to each module's home directory
      // rather than the Akka root, some tests depend on Akka root being working dir, so reset
      testGrouping in Test := {
        val original: Seq[Tests.Group] = (testGrouping in Test).value

        original.map { group ⇒
          group.runPolicy match {
            case Tests.SubProcess(forkOptions) ⇒
              group.copy(runPolicy = Tests.SubProcess(forkOptions.withWorkingDirectory(
                workingDirectory = Some(new File(System.getProperty("user.dir"))))))
            case _ ⇒ group
          }
        }
      },

      parallelExecution in Test := System.getProperty("akka.parallelExecution", parallelExecutionByDefault.toString).toBoolean,
      logBuffered in Test := System.getProperty("akka.logBufferedTests", "false").toBoolean,

      // show full stack traces and test case durations
      testOptions in Test += Tests.Argument("-oDF")) ++
      docLintingSettings

  lazy val docLintingSettings = Seq(
    javacOptions in compile ++= Seq("-Xdoclint:none"),
    javacOptions in test ++= Seq("-Xdoclint:none"),
    javacOptions in doc ++= Seq("-Xdoclint:none"))

  def loadSystemProperties(fileName: String): Unit = {
    import scala.collection.JavaConverters._
    val file = new File(fileName)
    if (file.exists()) {
      println("Loading system properties from file `" + fileName + "`")
      val in = new InputStreamReader(new FileInputStream(file), "UTF-8")
      val props = new Properties
      props.load(in)
      in.close()
      sys.props ++ props.asScala
    }
  }

  def majorMinor(version: String): Option[String] = """\d+\.\d+""".r.findFirstIn(version)
}
