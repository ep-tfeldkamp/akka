/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import sbt.Keys._
import java.io.File
import sbtwhitesource.WhiteSourcePlugin.autoImport.whitesourceIgnore

object Publish extends AutoPlugin {

  val defaultPublishTo = settingKey[File]("Default publish directory")

  override def trigger = allRequirements

  override lazy val projectSettings = Seq(
    crossPaths := false,
    pomExtra := akkaPomExtra,
    publishTo := akkaPublishTo.value,
    credentials ++= akkaCredentials,
    organizationName := "Lightbend Inc.",
    organizationHomepage := Some(url("http://www.lightbend.com")),
    publishMavenStyle := true,
    pomIncludeRepository := { x ⇒ false },
    defaultPublishTo := crossTarget.value / "repository")

  def akkaPomExtra = {
    <inceptionYear>2009</inceptionYear>
    <developers>
      <developer>
        <id>akka-contributors</id>
        <name>Akka Contributors</name>
        <email>akka-dev@googlegroups.com</email>
        <url>https://github.com/akka/akka/graphs/contributors</url>
      </developer>
    </developers>
  }

  private def akkaPublishTo = Def.setting {
    artifactoryRepo(version.value) orElse localRepo(defaultPublishTo.value)
  }

  private def artifactoryRepo(version: String): Option[Resolver] = {
    if (version.endsWith("-SNAPSHOT")) {
      None
    } else {
      Some(
        Resolver.url(
          "Artifactory third party library releases",
          new URL(s"http://artifactory.zentrale.local/ext-release-local")
        )(Resolver.mavenStylePatterns)
      )
    }
  }

  private def localRepo(repository: File) =
    Some(Resolver.file("Default Local Repository", repository))

  private def akkaCredentials: Seq[Credentials] =
    Option(System.getProperty("akka.publish.credentials", null)).map(f ⇒ Credentials(new File(f))).toSeq
}

/**
  * For projects that are not to be published.
  */
object NoPublish extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  override def projectSettings = Seq(
    skip in publish := true,
    sources in (Compile, doc) := Seq.empty,
    whitesourceIgnore := true
  )
}

object DeployRsync extends AutoPlugin {
  import scala.sys.process._
  import sbt.complete.DefaultParsers._

  override def requires = plugins.JvmPlugin

  trait Keys {
    val deployRsyncArtifact = taskKey[Seq[(File, String)]]("File or directory and a path to deploy to")
    val deployRsync = inputKey[Unit]("Deploy using SCP")
  }

  object autoImport extends Keys
  import autoImport._

  override def projectSettings = Seq(
    deployRsync := {
      val (_, host) = (Space ~ StringBasic).parsed
      deployRsyncArtifact.value.foreach {
        case (from, to) ⇒ s"rsync -rvz $from/ $host:$to"!
      }
    }
  )
}
